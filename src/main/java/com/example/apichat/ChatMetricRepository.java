package com.example.apichat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface ChatMetricRepository extends JpaRepository<ChatMetricEntity, Long> {
    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);
}
