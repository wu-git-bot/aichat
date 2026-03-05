package com.example.apichat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/reports")
public class AdminReportController {

    private final AppointmentRepository appointmentRepository;
    private final ChatMetricRepository chatMetricRepository;

    public AdminReportController(AppointmentRepository appointmentRepository, ChatMetricRepository chatMetricRepository) {
        this.appointmentRepository = appointmentRepository;
        this.chatMetricRepository = chatMetricRepository;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(value = "days", required = false, defaultValue = "7") int days) {
        int d = Math.max(1, Math.min(days, 30));
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(d - 1L);
        LocalDateTime from = startDate.atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay();

        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 0; i < d; i++) {
            LocalDate day = startDate.plusDays(i);
            long booked = appointmentRepository.findByDateAndStatus(day, "BOOKED").size();
            long canceled = appointmentRepository.findByDateAndStatus(day, "CANCELED").size();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", String.valueOf(day));
            row.put("booked", booked);
            row.put("canceled", canceled);
            trend.add(row);
        }

        List<AppointmentEntity> bookedAppointments = appointmentRepository.findByStatus("BOOKED");
        Map<String, Long> topReasons = bookedAppointments.stream()
                .collect(Collectors.groupingBy(a -> {
                    String r = a.getReason() == null ? "" : a.getReason().trim();
                    return r.isEmpty() ? "未填写" : r;
                }, Collectors.counting()));

        List<Map<String, Object>> topReasonList = topReasons.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("reason", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .toList();

        long chatMessages = chatMetricRepository.countByCreatedAtBetween(from, to);
        long bookedCount = appointmentRepository.countByStatusAndCreatedAtBetween("BOOKED", from, to);
        double conversion = chatMessages == 0 ? 0.0 : ((double) bookedCount / (double) chatMessages);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", d);
        result.put("trend", trend);
        result.put("topReasons", topReasonList);
        result.put("chatMessages", chatMessages);
        result.put("bookedAppointments", bookedCount);
        result.put("conversionRate", conversion);
        return result;
    }
}
