package com.example.apichat;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/expert/me")
public class ExpertPortalController {

    private final ExpertScheduleRepository expertScheduleRepository;
    private final AppointmentRepository appointmentRepository;

    public ExpertPortalController(ExpertScheduleRepository expertScheduleRepository,
                                  AppointmentRepository appointmentRepository) {
        this.expertScheduleRepository = expertScheduleRepository;
        this.appointmentRepository = appointmentRepository;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        String expert = currentExpert();
        List<Map<String, Object>> schedules = expertScheduleRepository.findAll().stream()
                .filter(item -> expert.equalsIgnoreCase(item.getExpert()))
                .sorted(Comparator.comparing(ExpertScheduleEntity::getDate).thenComparing(ExpertScheduleEntity::getTime))
                .map(this::scheduleMap)
                .collect(Collectors.toList());

        List<Map<String, Object>> appointments = appointmentRepository.findAll().stream()
                .filter(item -> expert.equalsIgnoreCase(item.getExpert()))
                .sorted(Comparator.comparing(AppointmentEntity::getDate).thenComparing(AppointmentEntity::getTime))
                .map(this::appointmentMap)
                .collect(Collectors.toList());

        long enabledSlots = schedules.stream().filter(item -> Boolean.TRUE.equals(item.get("enabled"))).count();
        long bookedCount = appointments.stream().filter(item -> "BOOKED".equals(item.get("status"))).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expert", expert);
        result.put("today", String.valueOf(LocalDate.now()));
        result.put("enabledSlots", enabledSlots);
        result.put("bookedCount", bookedCount);
        result.put("schedules", schedules);
        result.put("appointments", appointments);
        return result;
    }

    @PatchMapping("/schedules/{id}")
    public ResponseEntity<?> toggleMySchedule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        ExpertScheduleEntity entity = expertScheduleRepository.findById(id).orElse(null);
        if (entity == null) {
            return ResponseEntity.notFound().build();
        }
        if (!currentExpert().equalsIgnoreCase(entity.getExpert())) {
            return ResponseEntity.status(403).body(Map.of("message", "forbidden"));
        }
        Object enabled = body.get("enabled");
        if (!(enabled instanceof Boolean value)) {
            return ResponseEntity.badRequest().body(Map.of("message", "enabled required"));
        }
        entity.setEnabled(value);
        expertScheduleRepository.save(entity);
        return ResponseEntity.ok(scheduleMap(entity));
    }

    private String currentExpert() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalStateException("expert not authenticated");
        }
        return authentication.getName().trim();
    }

    private Map<String, Object> scheduleMap(ExpertScheduleEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("expert", entity.getExpert());
        map.put("date", String.valueOf(entity.getDate()));
        map.put("time", entity.getTime());
        map.put("enabled", entity.getEnabled());
        return map;
    }

    private Map<String, Object> appointmentMap(AppointmentEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
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
