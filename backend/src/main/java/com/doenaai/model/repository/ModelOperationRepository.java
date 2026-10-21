package com.doenaai.model.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** 외부 요청 전에 실행 기록을 확정하고 결과가 달라질 때마다 변경 이력을 추가한다. */
@Repository
@RequiredArgsConstructor
public class ModelOperationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Transactional
    public boolean create(UUID id, String action, UUID actor, String role, String target, Map<String, Object> request) {
        int inserted = jdbc.update("""
                insert into public.model_management_operations(id, action, actor_id, server_role, target_key, request)
                values (?, ?, ?, ?, ?, ?::jsonb) on conflict (id) do nothing
                """, id, action, actor, role, target, mapper.writeValueAsString(request));
        if (inserted == 1) jdbc.update("""
                insert into public.model_management_events(operation_id, status, summary)
                values (?, 'PENDING', '{}'::jsonb)
                """, id);
        return inserted == 1;
    }

    public Optional<Map<String, Object>> find(UUID id) {
        return jdbc.query("select * from public.model_management_operations where id = ?", this::row, id).stream().findFirst();
    }

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.query("select * from public.model_management_operations order by created_at desc, id desc limit ?", this::row, limit);
    }

    public List<Map<String, Object>> training(String target, int limit) {
        return jdbc.query("""
                select * from public.model_management_operations where target_key = ? and action = 'TRAINING_START'
                order by created_at desc, id desc limit ?
                """, this::row, target, limit);
    }

    public List<Map<String, Object>> events(UUID id) {
        return jdbc.query("""
                select id, status, error_code, summary, created_at from public.model_management_events
                where operation_id = ? order by id desc limit 100
                """, (row, index) -> {
            var value = new LinkedHashMap<String, Object>();
            value.put("id", row.getLong("id"));
            value.put("status", row.getString("status"));
            value.put("errorCode", row.getString("error_code"));
            value.put("summary", json(row.getString("summary")));
            value.put("createdAt", row.getTimestamp("created_at").toInstant().toString());
            return value;
        }, id);
    }

    @Transactional
    public Map<String, Object> record(UUID id, String status, String code, Map<String, Object> result) {
        var current = jdbc.query("select * from public.model_management_operations where id = ? for update", this::row, id).getFirst();
        // 늦게 도착한 조회 응답으로 완료 상태가 진행 상태로 되돌아가지 않게 한다.
        if (Set.of("SUCCEEDED", "FAILED", "CANCELLED", "APPLIED", "ROLLED_BACK", "PASSED").contains(current.get("status"))
                && Set.of("PENDING", "PREPARING", "TRAINING", "VERIFYING", "UNKNOWN").contains(status)) return current;
        // 같은 상태를 조회한 것만으로 반복 이력을 만들지 않는다.
        if (status.equals(current.get("status")) && Objects.equals(code, current.get("errorCode")) && result.equals(current.get("result"))) return current;
        jdbc.update("""
                update public.model_management_operations set status = ?, error_code = ?, result = ?::jsonb,
                    before_model = coalesce(?, before_model), after_model = coalesce(?, after_model), updated_at = now()
                where id = ?
                """, status, code, mapper.writeValueAsString(result), result.get("previousModel"), result.get("model"), id);
        jdbc.update("""
                insert into public.model_management_events(operation_id, status, error_code, summary)
                values (?, ?, ?, ?::jsonb)
                """, id, status, code, mapper.writeValueAsString(result));
        return find(id).orElseThrow();
    }

    private Map<String, Object> row(ResultSet row, int index) throws SQLException {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", row.getObject("id", UUID.class).toString());
        value.put("action", row.getString("action"));
        value.put("status", row.getString("status"));
        value.put("actorId", row.getObject("actor_id", UUID.class).toString());
        value.put("serverRole", row.getString("server_role"));
        value.put("targetKey", row.getString("target_key"));
        value.put("request", json(row.getString("request")));
        value.put("result", json(row.getString("result")));
        value.put("beforeModel", row.getString("before_model"));
        value.put("afterModel", row.getString("after_model"));
        value.put("errorCode", row.getString("error_code"));
        value.put("createdAt", row.getTimestamp("created_at").toInstant().toString());
        value.put("updatedAt", row.getTimestamp("updated_at").toInstant().toString());
        return value;
    }

    private Map<String, Object> json(String text) {
        return mapper.readValue(text, new TypeReference<>() { });
    }
}
