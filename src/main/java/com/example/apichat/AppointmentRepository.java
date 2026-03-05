package com.example.apichat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<AppointmentEntity, Long> {
    Optional<AppointmentEntity> findByDateAndTimeAndExpertAndStatus(LocalDate date, String time, String expert, String status);
    List<AppointmentEntity> findByDateAndStatus(LocalDate date, String status);
    List<AppointmentEntity> findByStatus(String status);
    List<AppointmentEntity> findByCreatedAtBetween(LocalDateTime from, LocalDateTime to);
    long countByStatusAndCreatedAtBetween(String status, LocalDateTime from, LocalDateTime to);
}
