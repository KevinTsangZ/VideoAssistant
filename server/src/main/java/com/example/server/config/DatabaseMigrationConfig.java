package com.example.server.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseMigrationConfig implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseMigrationConfig(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureFreeUploadUsedColumn();
    }

    private void ensureFreeUploadUsedColumn() {
        try {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'free_upload_used'",
                    Integer.class
            );
            if (exists != null && exists == 0) {
                jdbcTemplate.execute("ALTER TABLE users ADD COLUMN free_upload_used INT NOT NULL DEFAULT 0");
                jdbcTemplate.execute(
                        "UPDATE users u " +
                                "LEFT JOIN (SELECT user_id, COUNT(*) AS used_count FROM media_files WHERE user_id IS NOT NULL GROUP BY user_id) m " +
                                "ON u.id = m.user_id " +
                                "SET u.free_upload_used = COALESCE(m.used_count, 0)"
                );
                System.out.println("Database migrated: users.free_upload_used added");
            }
        } catch (Exception e) {
            System.err.println("Database migration skipped: " + e.getMessage());
        }
    }
}
