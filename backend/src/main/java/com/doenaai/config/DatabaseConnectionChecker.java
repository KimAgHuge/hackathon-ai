package com.doenaai.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseConnectionChecker {

    private final JdbcTemplate jdbcTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void checkConnection() {

        Integer result =
                jdbcTemplate.queryForObject(
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