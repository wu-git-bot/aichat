package com.example.apichat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final AppointmentRepository repository;

    public AdminController(AppointmentRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/appointments")
    public List<Map<String, String>> getAllAppointments() {
        List<AppointmentEntity> entities = repository.findAll();
        if (entities.isEmpty()) return new ArrayList<>();

        return entities.stream().map(entity -> {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("id", String.valueOf(entity.getId()));
            map.put("name", entity.getName());
            map.put("email", entity.getEmail());
            map.put("date", String.valueOf(entity.getDate()));
            map.put("time", entity.getTime());
            map.put("expert", entity.getExpert());
            map.put("status", entity.getStatus());
            map.put("reason", entity.getReason());
            return map;
        }).collect(Collectors.toList());
    }
}

