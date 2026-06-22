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
            if (!columnExists("users", "free_upload_used")) {
                jdbcTemplate.execute("ALTER TABLE users ADD COLUMN free_upload_used INT NOT NULL DEFAULT 0");
                jdbcTemplate.execute(
                        "UPDATE users u " +
                                "LEFT JOIN (SELECT user_id, COUNT(*) AS used_count FROM media_files WHERE user_id IS NOT NULL GROUP BY user_id) m " +
                                "ON u.id = m.user_id " +
                                "SET u.free_upload_used = COALESCE(m.used_count, 0)"
                );
                System.out.println("Database migrated: users.free_upload_used added");
            }
            if (!columnExists("users", "ai_base_url")) {
                jdbcTemplate.execute("ALTER TABLE users ADD COLUMN ai_base_url VARCHAR(255) NULL");
                System.out.println("Database migrated: users.ai_base_url added");
            }
            if (!columnExists("users", "ai_api_key")) {
                jdbcTemplate.execute("ALTER TABLE users ADD COLUMN ai_api_key VARCHAR(512) NULL");
                System.out.println("Database migrated: users.ai_api_key added");
            }
            if (!columnExists("users", "ai_model")) {
                jdbcTemplate.execute("ALTER TABLE users ADD COLUMN ai_model VARCHAR(128) NULL");
                System.out.println("Database migrated: users.ai_model added");
            }
            if (!columnExists("media_files", "file_md5")) {
                jdbcTemplate.execute("ALTER TABLE media_files ADD COLUMN file_md5 VARCHAR(64) NULL");
                System.out.println("Database migrated: media_files.file_md5 added");
            }
        } catch (Exception e) {
            System.err.println("Database migration skipped: " + e.getMessage());
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class,
                tableName,
                columnName
        );
        return exists != null && exists > 0;
    }
}
