package com.doenaai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseConnectionChecker {

    private static final Logger log =
            LoggerFactory.getLogger(DatabaseConnectionChecker.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseConnectionChecker(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void checkConnection() {

        Integer result = jdbcTemplate.queryForObject(
                "select 1",
                Integer.class
        );

        if (result == null || result != 1) {
            throw new IllegalStateException(
                    "Supabase PostgreSQL 연결 확인에 실패했습니다."
            );
        }

        log.info("Supabase PostgreSQL 연결 확인 완료");
    }
}