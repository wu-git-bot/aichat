package com.example.apichat;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ClassPathResource;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final Path csvPath = Paths.get("D:\\jdk17\\apichat\\src\\main\\resources\\data\\appointments.csv");
    private final AtomicInteger idCounter = new AtomicInteger(3); // 从3开始，自增


    @GetMapping
    public List<Map<String, String>> getAllAppointments() throws IOException {//输出预约信息
        if (!Files.exists(csvPath)) return new ArrayList<>();
        List<String> lines = Files.readAllLines(csvPath);
        return lines.stream()
                .skip(1)
                .map(line -> {
                    String[] parts = line.split(",", -1);
                    Map<String, String> map = new LinkedHashMap<>();
                    map.put("id", parts[0]);
                    map.put("name", parts[1]);
                    map.put("email", parts[2]);
                    map.put("date", parts[3]);
                    map.put("time", parts[4]);
                    map.put("reason", parts[5]);
                    return map;
                }).collect(Collectors.toList());
    }

    @PostMapping
    public ResponseEntity<String> createAppointment(@RequestBody Map<String, String> appointment) throws IOException {
        if (!Files.exists(csvPath)) {
            Files.write(csvPath, Collections.singletonList("id,name,email,date,time,reason"));
        }

        // 检查是否重复预约：日期+时间段
        List<String> lines = Files.readAllLines(csvPath);
        String selectedDate = appointment.get("date");
        String selectedTime = appointment.get("time");

        for (String line : lines.subList(1, lines.size())) { // 跳过表头
            String[] parts = line.split(",", -1);
            String date = parts[3];
            String time = parts[4];
            if (selectedDate.equals(date) && selectedTime.equals(time)) {
                return ResponseEntity.status(409).body("提交失败");
            }
        }

        // 写入新预约
        int id = idCounter.getAndIncrement();
        String line = String.format("%d,%s,%s,%s,%s,%s",
                id,
                appointment.get("name"),
                appointment.get("email"),
                selectedDate,
                selectedTime,
                appointment.get("reason"));
        Files.write(csvPath, Collections.singletonList(line), StandardOpenOption.APPEND);
        return ResponseEntity.ok("预约成功！");
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteAppointment(@PathVariable String id) throws IOException {//删除预约信息
        if (!Files.exists(csvPath)) return ResponseEntity.notFound().build();
        List<String> lines = Files.readAllLines(csvPath);
        List<String> updated = new ArrayList<>();
        updated.add(lines.get(0)); // header
        for (int i = 1; i < lines.size(); i++) {
            if (!lines.get(i).startsWith(id + ",")) {
                updated.add(lines.get(i));
            }
        }
        Files.write(csvPath, updated, StandardOpenOption.TRUNCATE_EXISTING);
        return ResponseEntity.ok("已删除");
    }
}
