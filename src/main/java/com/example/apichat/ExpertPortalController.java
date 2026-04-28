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
        
        // 获取该专家的排班
        List<Map<String, Object>> schedules = expertScheduleRepository.findAll().stream()
                .filter(item -> expert.equalsIgnoreCase(item.getExpert()))
                .sorted(Comparator.comparing(ExpertScheduleEntity::getDate).thenComparing(ExpertScheduleEntity::getTime))
                .map(this::scheduleMap)
                .collect(Collectors.toList());

        // 获取分配给该专家的预约
        // 1. 直接指定了该专家名字的预约
        // 2. 预约中 expert 为空或 "SYSTEM" 但该专家有对应的可用排班的预约
        List<Map<String, Object>> appointments = appointmentRepository.findAll().stream()
                .filter(item -> isAppointmentForExpert(item, expert))
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

    /**
     * 判断一个预约是否属于当前专家
     * 
     * 预约被视为分配给专家的情况：
     * 1. 预约的 expert 字段与专家名字完全匹配（不区分大小写）
     * 2. 预约的 expert 为 "SYSTEM" 或为空，但专家在该时间段有启用的排班
     */
    private boolean isAppointmentForExpert(AppointmentEntity appointment, String expertName) {
        String appointmentExpert = appointment.getExpert();
        
        // 情况1：直接指定了该专家
        if (appointmentExpert != null && !appointmentExpert.isBlank() 
                && expertName.equalsIgnoreCase(appointmentExpert.trim())) {
            return true;
        }
        
        // 情况2：预约是给 SYSTEM 或空，但该专家在该时间段有可用排班
        if ((appointmentExpert == null || appointmentExpert.isBlank() || "SYSTEM".equalsIgnoreCase(appointmentExpert))) {
            boolean hasSchedule = expertScheduleRepository.findAll().stream()
                    .anyMatch(schedule -> 
                        expertName.equalsIgnoreCase(schedule.getExpert())
                        && appointment.getDate().equals(schedule.getDate())
                        && appointment.getTime().equals(schedule.getTime())
                        && Boolean.TRUE.equals(schedule.getEnabled())
                    );
            return hasSchedule;
        }
        
        return false;
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
