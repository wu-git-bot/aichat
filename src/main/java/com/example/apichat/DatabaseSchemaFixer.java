package com.example.apichat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSchemaFixer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaFixer.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaFixer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void fixAppointmentsTable() {
        try {
            if (!tableExists("appointments")) {
                return;
            }

            if (!columnExists("appointments", "expert")) {
                jdbcTemplate.execute("ALTER TABLE appointments ADD COLUMN expert VARCHAR(64) NULL");
                log.info("schema fix: added appointments.expert");
            }
            if (!columnExists("appointments", "status")) {
                jdbcTemplate.execute("ALTER TABLE appointments ADD COLUMN status VARCHAR(16) NULL");
                log.info("schema fix: added appointments.status");
            }
            if (!columnExists("appointments", "created_at")) {
                jdbcTemplate.execute("ALTER TABLE appointments ADD COLUMN created_at DATETIME NULL");
                log.info("schema fix: added appointments.created_at");
            }
            if (!columnExists("appointments", "updated_at")) {
                jdbcTemplate.execute("ALTER TABLE appointments ADD COLUMN updated_at DATETIME NULL");
                log.info("schema fix: added appointments.updated_at");
            }

            jdbcTemplate.execute("UPDATE appointments SET expert='系统分配' WHERE expert IS NULL OR expert=''");
            jdbcTemplate.execute("UPDATE appointments SET status='BOOKED' WHERE status IS NULL OR status=''");
            jdbcTemplate.execute("UPDATE appointments SET created_at=NOW() WHERE created_at IS NULL");
            jdbcTemplate.execute("UPDATE appointments SET updated_at=NOW() WHERE updated_at IS NULL");

            jdbcTemplate.execute("ALTER TABLE appointments MODIFY COLUMN expert VARCHAR(64) NOT NULL");
            jdbcTemplate.execute("ALTER TABLE appointments MODIFY COLUMN status VARCHAR(16) NOT NULL");
            jdbcTemplate.execute("ALTER TABLE appointments MODIFY COLUMN created_at DATETIME NOT NULL");
            jdbcTemplate.execute("ALTER TABLE appointments MODIFY COLUMN updated_at DATETIME NULL");

            log.info("schema fix: appointments table is aligned");
        } catch (Exception e) {
            log.warn("schema fix skipped: {}", e.getMessage());
        }
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName
        );
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class,
                tableName,
                columnName
        );
        return count != null && count > 0;
    }
}
