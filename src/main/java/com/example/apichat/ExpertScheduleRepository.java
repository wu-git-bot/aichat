package com.example.apichat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExpertScheduleRepository extends JpaRepository<ExpertScheduleEntity, Long> {
    List<ExpertScheduleEntity> findByDateAndEnabled(LocalDate date, Boolean enabled);
    Optional<ExpertScheduleEntity> findByDateAndTimeAndExpertAndEnabled(LocalDate date, String time, String expert, Boolean enabled);
    List<ExpertScheduleEntity> findByEnabled(Boolean enabled);
}
