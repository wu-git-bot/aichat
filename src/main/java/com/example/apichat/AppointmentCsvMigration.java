package com.example.apichat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class AppointmentCsvMigration {

    private static final Logger log = LoggerFactory.getLogger(AppointmentCsvMigration.class);
    private static final Path CSV_PATH = Paths.get("src/main/resources/data/appointments.csv");

    private final AppointmentRepository repository;

    public AppointmentCsvMigration(AppointmentRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrate() {
        try {
            if (!Files.exists(CSV_PATH)) return;
            if (repository.count() > 0) return;

            List<String> lines = Files.readAllLines(CSV_PATH);
            if (lines.size() <= 1) return;

            int imported = 0;
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",", -1);
                if (parts.length < 6) continue;

                LocalDate date;
                try {
                    date = LocalDate.parse(parts[3].trim());
                } catch (Exception ex) {
                    continue;
                }

                String time = parts[4].trim();
                if (repository.findByDateAndTimeAndExpertAndStatus(date, time, "legacy-import", "BOOKED").isPresent()) {
                    continue;
                }

                AppointmentEntity entity = new AppointmentEntity();
                entity.setName(parts[1].trim());
                entity.setEmail(parts[2].trim());
                entity.setDate(date);
                entity.setTime(time);
                entity.setReason(parts[5].trim());
                entity.setExpert("legacy-import");
                entity.setStatus("BOOKED");
                entity.setCreatedAt(LocalDateTime.now());
                entity.setUpdatedAt(LocalDateTime.now());
                repository.save(entity);
                imported++;
            }
            log.info("Appointment CSV migration finished, imported={} from {}", imported, CSV_PATH);
        } catch (Exception e) {
            log.warn("Appointment CSV migration skipped due to error: {}", e.getMessage());
        }
    }
}
