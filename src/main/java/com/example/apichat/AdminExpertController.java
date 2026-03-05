package com.example.apichat;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/experts")
public class AdminExpertController {

    private final ExpertScheduleRepository expertScheduleRepository;

    public AdminExpertController(ExpertScheduleRepository expertScheduleRepository) {
        this.expertScheduleRepository = expertScheduleRepository;
    }

    @GetMapping("/schedules")
    public List<Map<String, Object>> listSchedules() {
        return expertScheduleRepository.findAll().stream()
                .sorted(Comparator.comparing(ExpertScheduleEntity::getDate).thenComparing(ExpertScheduleEntity::getTime))
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("expert", s.getExpert());
                    m.put("date", String.valueOf(s.getDate()));
                    m.put("time", s.getTime());
                    m.put("enabled", s.getEnabled());
                    return m;
                }).collect(Collectors.toList());
    }

    @PostMapping("/schedules")
    public ResponseEntity<?> createSchedule(@RequestBody Map<String, String> body) {
        String expert = body.getOrDefault("expert", "").trim();
        String dateStr = body.getOrDefault("date", "").trim();
        String time = body.getOrDefault("time", "").trim();
        if (expert.isBlank() || dateStr.isBlank() || time.isBlank()) {
            return ResponseEntity.badRequest().body("expert/date/time required");
        }

        LocalDate date;
        try {
            date = LocalDate.parse(dateStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("invalid date format");
        }

        ExpertScheduleEntity entity = new ExpertScheduleEntity();
        entity.setExpert(expert);
        entity.setDate(date);
        entity.setTime(time);
        entity.setEnabled(true);
        expertScheduleRepository.save(entity);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PatchMapping("/schedules/{id}")
    public ResponseEntity<?> toggleSchedule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        ExpertScheduleEntity entity = expertScheduleRepository.findById(id).orElse(null);
        if (entity == null) return ResponseEntity.notFound().build();

        Object enabled = body.get("enabled");
        if (enabled instanceof Boolean v) {
            entity.setEnabled(v);
            expertScheduleRepository.save(entity);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/schedules/{id}")
    public ResponseEntity<?> deleteSchedule(@PathVariable Long id) {
        if (!expertScheduleRepository.existsById(id)) return ResponseEntity.notFound().build();
        expertScheduleRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
