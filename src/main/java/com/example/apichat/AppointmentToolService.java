package com.example.apichat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AppointmentToolService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private static final List<String> TIME_SLOTS = List.of(
            "8:00-9:00",
            "9:00-10:00",
            "10:00-11:00",
            "14:00-15:00",
            "15:00-16:00",
            "16:00-17:00"
    );

    private final AppointmentRepository appointmentRepository;
    private final ExpertScheduleRepository expertScheduleRepository;
    private final String defaultUserUsername;

    public AppointmentToolService(AppointmentRepository appointmentRepository,
                                  ExpertScheduleRepository expertScheduleRepository,
                                  @Value("${app.security.users.user.username:user}") String defaultUserUsername) {
        this.appointmentRepository = appointmentRepository;
        this.expertScheduleRepository = expertScheduleRepository;
        this.defaultUserUsername = defaultUserUsername;
    }

    public Map<String, Object> createAppointment(Map<String, Object> args) {
        String name = str(args.get("name"));
        String email = str(args.get("email"));
        String dateStr = str(args.get("date"));
        String time = str(args.get("time"));
        String expert = str(args.get("expert"));

        if (name.isBlank() || email.isBlank() || dateStr.isBlank() || time.isBlank()) {
            throw new IllegalArgumentException("name/email/date/time required");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("invalid email format");
        }

        LocalDate date = parseDate(dateStr);
        validateTimeSlot(time);
        validateAppointmentTime(date, time);
        String pickedExpert = pickExpert(date, time, expert);
        if (pickedExpert == null || pickedExpert.isBlank()) {
            return unavailableResult("No available expert for this slot", date, time, expert);
        }

        boolean occupied = appointmentRepository.findByDateAndTimeAndExpertAndStatus(date, time, pickedExpert, "BOOKED").isPresent();
        if (occupied) {
            return unavailableResult("Appointment slot already booked", date, time, expert);
        }

        AppointmentEntity entity = new AppointmentEntity();
        entity.setName(name);
        entity.setEmail(email);
        entity.setOwnerUsername(currentPrincipal());
        entity.setDate(date);
        entity.setTime(time);
        entity.setReason(str(args.get("reason")));
        entity.setExpert(pickedExpert);
        entity.setStatus("BOOKED");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        appointmentRepository.save(entity);

        return toMap(entity);
    }

    public List<Map<String, Object>> listAppointments(Map<String, Object> args) {
        String id = str(args.get("id"));
        String dateStr = str(args.get("date"));
        String time = str(args.get("time"));
        String status = str(args.get("status"));
        String email = str(args.get("email"));
        String name = str(args.get("name"));
        String ownerUsername = str(args.get("ownerUsername"));

        return appointmentRepository.findAll().stream()
                .filter(a -> id.isBlank() || Objects.equals(String.valueOf(a.getId()), id))
                .filter(a -> dateStr.isBlank() || Objects.equals(String.valueOf(a.getDate()), dateStr))
                .filter(a -> time.isBlank() || Objects.equals(a.getTime(), time))
                .filter(a -> status.isBlank() || Objects.equals(a.getStatus(), status))
                .filter(a -> ownerUsername.isBlank() || canMatchOwnerUsername(ownerUsername, a))
                .filter(a -> email.isBlank() || email.equalsIgnoreCase(str(a.getEmail())))
                .filter(a -> name.isBlank() || name.equalsIgnoreCase(str(a.getName())))
                .sorted(Comparator.comparing(AppointmentEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    public Map<String, Object> rescheduleAppointment(Map<String, Object> args) {
        Long id = longValue(args.get("id"));
        AppointmentEntity entity = appointmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("appointment not found"));
        if (!"BOOKED".equals(entity.getStatus())) {
            throw new IllegalArgumentException("only BOOKED appointment can be rescheduled");
        }

        String dateStr = str(args.get("date"));
        String time = str(args.get("time"));
        String requestedExpert = str(args.getOrDefault("expert", entity.getExpert()));
        if (dateStr.isBlank() || time.isBlank()) {
            throw new IllegalArgumentException("date/time required");
        }

        LocalDate date = parseDate(dateStr);
        validateTimeSlot(time);
        validateAppointmentTime(date, time);
        String pickedExpert = pickExpert(date, time, requestedExpert);
        if (pickedExpert == null || pickedExpert.isBlank()) {
            return unavailableResult("No available expert for this slot", date, time, requestedExpert);
        }

        boolean occupied = appointmentRepository.findByDateAndTimeAndExpertAndStatus(date, time, pickedExpert, "BOOKED")
                .filter(a -> !Objects.equals(a.getId(), id))
                .isPresent();
        if (occupied) {
            return unavailableResult("Appointment slot already booked", date, time, requestedExpert);
        }

        entity.setDate(date);
        entity.setTime(time);
        entity.setExpert(pickedExpert);
        entity.setUpdatedAt(LocalDateTime.now());
        appointmentRepository.save(entity);
        return toMap(entity);
    }

    public Map<String, Object> cancelAppointment(Map<String, Object> args) {
        Long id = longValue(args.get("id"));
        AppointmentEntity entity = appointmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("appointment not found"));
        entity.setStatus("CANCELED");
        entity.setUpdatedAt(LocalDateTime.now());
        appointmentRepository.save(entity);
        return toMap(entity);
    }

    public List<Map<String, String>> suggestSlots(Map<String, Object> args) {
        String dateStr = str(args.get("date"));
        if (dateStr.isBlank()) {
            throw new IllegalArgumentException("date required");
        }
        LocalDate date = parseDate(dateStr);
        String fromTime = str(args.get("fromTime"));
        String expert = str(args.get("expert"));
        int limit = intValue(args.get("limit"), 3);
        return recommendSlots(date, fromTime, expert, limit);
    }

    private Map<String, Object> unavailableResult(String message, LocalDate date, String time, String expert) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message);
        result.put("suggestions", recommendSlots(date, time, expert, 3));
        return result;
    }

    private String pickExpert(LocalDate date, String time, String requestedExpert) {
        List<ExpertScheduleEntity> schedules = expertScheduleRepository.findByDateAndEnabled(date, true).stream()
                .filter(s -> time.equals(s.getTime()))
                .collect(Collectors.toList());

        if (schedules.isEmpty()) {
            String fallback = requestedExpert.isBlank() ? "SYSTEM" : requestedExpert;
            boolean free = appointmentRepository.findByDateAndTimeAndExpertAndStatus(date, time, fallback, "BOOKED").isEmpty();
            return free ? fallback : null;
        }

        List<String> candidates = schedules.stream().map(ExpertScheduleEntity::getExpert).distinct().toList();
        if (!requestedExpert.isBlank()) {
            if (!candidates.contains(requestedExpert)) {
                return null;
            }
            boolean free = appointmentRepository.findByDateAndTimeAndExpertAndStatus(date, time, requestedExpert, "BOOKED").isEmpty();
            return free ? requestedExpert : null;
        }

        for (String expert : candidates) {
            boolean free = appointmentRepository.findByDateAndTimeAndExpertAndStatus(date, time, expert, "BOOKED").isEmpty();
            if (free) {
                return expert;
            }
        }
        return null;
    }

    private List<Map<String, String>> recommendSlots(LocalDate startDate, String fromTime, String preferredExpert, int limit) {
        List<Map<String, String>> result = new ArrayList<>();
        int safeLimit = Math.max(1, Math.min(limit, 10));
        int fromIndex = fromTime == null || fromTime.isBlank() ? 0 : Math.max(TIME_SLOTS.indexOf(fromTime) + 1, 0);

        for (int dayOffset = 0; dayOffset < 14 && result.size() < safeLimit; dayOffset++) {
            LocalDate date = startDate.plusDays(dayOffset);
            int begin = dayOffset == 0 ? fromIndex : 0;
            for (int i = begin; i < TIME_SLOTS.size() && result.size() < safeLimit; i++) {
                String slot = TIME_SLOTS.get(i);
                String expert = pickExpert(date, slot, preferredExpert == null ? "" : preferredExpert);
                if (expert != null) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("date", String.valueOf(date));
                    item.put("time", slot);
                    item.put("expert", expert);
                    result.add(item);
                }
            }
        }
        return result;
    }

    private Map<String, Object> toMap(AppointmentEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", String.valueOf(entity.getId()));
        map.put("name", entity.getName());
        map.put("email", entity.getEmail());
        map.put("date", String.valueOf(entity.getDate()));
        map.put("time", entity.getTime());
        map.put("reason", entity.getReason());
        map.put("expert", entity.getExpert());
        map.put("status", entity.getStatus());
        return map;
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid date format, expected yyyy-MM-dd");
        }
    }

    private Long longValue(Object value) {
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid id");
        }
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void validateTimeSlot(String time) {
        if (!TIME_SLOTS.contains(time)) {
            throw new IllegalArgumentException("invalid time slot");
        }
    }

    private void validateAppointmentTime(LocalDate date, String timeSlot) {
        LocalTime start = parseStartTime(timeSlot);
        if (LocalDateTime.of(date, start).isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("appointment time cannot be in the past");
        }
    }

    private LocalTime parseStartTime(String timeSlot) {
        String[] parts = timeSlot.split("-");
        if (parts.length == 0) {
            throw new IllegalArgumentException("invalid time slot");
        }
        try {
            return LocalTime.parse(parts[0]);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid time slot");
        }
    }

    private String currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            return null;
        }
        String principal = authentication.getName().trim();
        return principal.isBlank() ? null : principal;
    }

    private boolean canMatchOwnerUsername(String ownerUsername, AppointmentEntity entity) {
        if (ownerUsername.equalsIgnoreCase(defaultUserUsername) && canAccessAllAppointments()) {
            return true;
        }
        return ownerUsername.equalsIgnoreCase(str(entity.getOwnerUsername()));
    }

    private boolean canAccessAllAppointments() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }

        boolean admin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
        if (admin) {
            return true;
        }

        String principal = authentication.getName();
        return principal != null && principal.equalsIgnoreCase(defaultUserUsername);
    }
}
