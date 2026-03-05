package com.example.apichat;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

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

    public AppointmentController(AppointmentRepository appointmentRepository,
                                 ExpertScheduleRepository expertScheduleRepository) {
        this.appointmentRepository = appointmentRepository;
        this.expertScheduleRepository = expertScheduleRepository;
    }

    @GetMapping
    public List<Map<String, String>> getAllAppointments() {
        return appointmentRepository.findAll().stream()
                .sorted(Comparator.comparing(AppointmentEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    @PostMapping
    public ResponseEntity<?> createAppointment(@RequestBody Map<String, String> appointment) {
        String selectedDate = appointment.getOrDefault("date", "").trim();
        String selectedTime = appointment.getOrDefault("time", "").trim();
        String requestedExpert = appointment.getOrDefault("expert", "").trim();

        if (selectedDate.isEmpty() || selectedTime.isEmpty()) {
            return ResponseEntity.badRequest().body("date/time required");
        }

        LocalDate date;
        try {
            date = LocalDate.parse(selectedDate);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("invalid date format, expected yyyy-MM-dd");
        }

        String expert = pickExpert(date, selectedTime, requestedExpert);
        if (expert == null || expert.isBlank()) {
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("message", "No available expert for this slot");
            conflict.put("suggestions", recommendSlots(date, selectedTime, requestedExpert, 3));
            return ResponseEntity.status(409).body(conflict);
        }

        if (appointmentRepository.findByDateAndTimeAndExpertAndStatus(date, selectedTime, expert, "BOOKED").isPresent()) {
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("message", "Appointment slot already booked");
            conflict.put("suggestions", recommendSlots(date, selectedTime, requestedExpert, 3));
            return ResponseEntity.status(409).body(conflict);
        }

        AppointmentEntity entity = new AppointmentEntity();
        entity.setName(appointment.getOrDefault("name", "").trim());
        entity.setEmail(appointment.getOrDefault("email", "").trim());
        entity.setDate(date);
        entity.setTime(selectedTime);
        entity.setReason(appointment.getOrDefault("reason", "").trim());
        entity.setExpert(expert);
        entity.setStatus("BOOKED");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        appointmentRepository.save(entity);
        return ResponseEntity.ok("Appointment created");
    }

    @PutMapping("/{id}/reschedule")
    public ResponseEntity<?> reschedule(@PathVariable Long id, @RequestBody Map<String, String> body) {
        AppointmentEntity entity = appointmentRepository.findById(id).orElse(null);
        if (entity == null) return ResponseEntity.notFound().build();
        if (!"BOOKED".equals(entity.getStatus())) {
            return ResponseEntity.badRequest().body("only BOOKED appointment can be rescheduled");
        }

        String dateStr = body.getOrDefault("date", "").trim();
        String time = body.getOrDefault("time", "").trim();
        String requestedExpert = body.getOrDefault("expert", entity.getExpert()).trim();

        LocalDate date;
        try {
            date = LocalDate.parse(dateStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("invalid date format, expected yyyy-MM-dd");
        }

        String expert = pickExpert(date, time, requestedExpert);
        if (expert == null || expert.isBlank()) {
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("message", "No available expert for this slot");
            conflict.put("suggestions", recommendSlots(date, time, requestedExpert, 3));
            return ResponseEntity.status(409).body(conflict);
        }

        boolean occupied = appointmentRepository.findByDateAndTimeAndExpertAndStatus(date, time, expert, "BOOKED")
                .filter(a -> !Objects.equals(a.getId(), id)).isPresent();
        if (occupied) {
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("message", "Appointment slot already booked");
            conflict.put("suggestions", recommendSlots(date, time, requestedExpert, 3));
            return ResponseEntity.status(409).body(conflict);
        }

        entity.setDate(date);
        entity.setTime(time);
        entity.setExpert(expert);
        entity.setUpdatedAt(LocalDateTime.now());
        appointmentRepository.save(entity);
        return ResponseEntity.ok("Appointment rescheduled");
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id) {
        AppointmentEntity entity = appointmentRepository.findById(id).orElse(null);
        if (entity == null) return ResponseEntity.notFound().build();
        entity.setStatus("CANCELED");
        entity.setUpdatedAt(LocalDateTime.now());
        appointmentRepository.save(entity);
        return ResponseEntity.ok("Appointment canceled");
    }

    @GetMapping("/suggestions")
    public ResponseEntity<List<Map<String, String>>> suggestions(
            @RequestParam("date") String dateStr,
            @RequestParam(value = "fromTime", required = false, defaultValue = "") String fromTime,
            @RequestParam(value = "expert", required = false, defaultValue = "") String expert) {
        LocalDate date;
        try {
            date = LocalDate.parse(dateStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(recommendSlots(date, fromTime, expert, 3));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteAppointment(@PathVariable Long id) {
        if (!appointmentRepository.existsById(id)) return ResponseEntity.notFound().build();
        appointmentRepository.deleteById(id);
        return ResponseEntity.ok("Appointment deleted");
    }

    private String pickExpert(LocalDate date, String time, String requestedExpert) {
        List<ExpertScheduleEntity> schedules = expertScheduleRepository.findByDateAndEnabled(date, true).stream()
                .filter(s -> time.equals(s.getTime()))
                .collect(Collectors.toList());

        if (schedules.isEmpty()) {
            String fallback = (requestedExpert != null && !requestedExpert.isBlank()) ? requestedExpert : "系统分配";
            boolean free = appointmentRepository
                    .findByDateAndTimeAndExpertAndStatus(date, time, fallback, "BOOKED")
                    .isEmpty();
            return free ? fallback : null;
        }

        List<String> candidates = schedules.stream().map(ExpertScheduleEntity::getExpert).distinct().toList();

        if (requestedExpert != null && !requestedExpert.isBlank()) {
            if (!candidates.contains(requestedExpert)) return null;
            boolean free = appointmentRepository
                    .findByDateAndTimeAndExpertAndStatus(date, time, requestedExpert, "BOOKED")
                    .isEmpty();
            return free ? requestedExpert : null;
        }

        for (String expert : candidates) {
            boolean free = appointmentRepository
                    .findByDateAndTimeAndExpertAndStatus(date, time, expert, "BOOKED")
                    .isEmpty();
            if (free) return expert;
        }
        return null;
    }

    private List<Map<String, String>> recommendSlots(LocalDate startDate, String fromTime, String preferredExpert, int limit) {
        List<Map<String, String>> result = new ArrayList<>();
        int fromIdx = fromTime == null || fromTime.isBlank() ? 0 : Math.max(TIME_SLOTS.indexOf(fromTime) + 1, 0);

        for (int dayOffset = 0; dayOffset < 14 && result.size() < limit; dayOffset++) {
            LocalDate date = startDate.plusDays(dayOffset);
            int begin = dayOffset == 0 ? fromIdx : 0;
            for (int i = begin; i < TIME_SLOTS.size() && result.size() < limit; i++) {
                String slot = TIME_SLOTS.get(i);
                String expert = pickExpert(date, slot, preferredExpert);
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

    private Map<String, String> toMap(AppointmentEntity entity) {
        Map<String, String> map = new LinkedHashMap<>();
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
}
