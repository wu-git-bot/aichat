package com.example.apichat;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private static final String ADMIN_USER = "admin";
    private  static final String ADMIN_PASS = "123456";
    private boolean loggedIn = false;
    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody Map<String,String> credentials){
        if(ADMIN_USER.equals(credentials.get("username")) && ADMIN_PASS.equals(credentials.get("password"))){
            loggedIn = true;
            return ResponseEntity.ok("登陆成功");
        }
        else{
            return ResponseEntity.status(403).body("用户名或密码错误");
        }
    }

    private final Path csvPath = Paths.get("appointments.csv");

    @GetMapping("/appointments")
    public List<Map<String, String>> getAllAppointments() throws IOException {
        if (!Files.exists(csvPath)) return new ArrayList<>();

        List<String> lines = Files.readAllLines(csvPath);
        return lines.stream()
                .skip(1) // 跳过表头
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
                })
                .collect(Collectors.toList());
    }
}
