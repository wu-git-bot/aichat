package com.example.apichat;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
public class ChatMetricController {

    private final ChatMetricRepository chatMetricRepository;

    public ChatMetricController(ChatMetricRepository chatMetricRepository) {
        this.chatMetricRepository = chatMetricRepository;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> markChat(Authentication authentication) {
        String username = authentication == null ? "anonymous" : authentication.getName();
        ChatMetricEntity entity = new ChatMetricEntity();
        entity.setUsername(username);
        entity.setCreatedAt(LocalDateTime.now());
        chatMetricRepository.save(entity);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
