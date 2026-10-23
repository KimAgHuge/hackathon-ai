package com.doenaai.model.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
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

    public record Page(List<Map<String, Object>> operations, int page, int pageSize, long totalCount) { }

    /** 개수와 목록은 같은 시점의 자료를 읽고 요청한 페이지에 필요한 행만 가져온다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page page(int requestedPage, int size, String query, String status) {
        StringBuilder where = new StringBuilder(" where true");
        var parameters = new ArrayList<Object>();
        if (status != null && !status.isBlank()) {
            where.append(" and o.status = ?");
            parameters.add(status);
        }
        String search = Normalizer.normalize(query == null ? "" : query, Normalizer.Form.NFKC).strip();
        if (!search.isBlank()) for (String term : new LinkedHashSet<>(List.of(search.split("\\s+")))) {
            where.append(" and ").append(SEARCH_TEXT).append(" ilike ? escape '!'");
            parameters.add("%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        Long counted = jdbc.queryForObject("select count(*) from public.model_management_operations o" + where, Long.class, parameters.toArray());
        long total = counted == null ? 0 : counted;
        int page = (int) Math.min(requestedPage, total == 0 ? 0 : (total - 1) / size);
        var pageParameters = new ArrayList<>(parameters);
        pageParameters.add(size);
        pageParameters.add((long) page * size);
        var rows = jdbc.query("select o.* from public.model_management_operations o" + where
                + " order by o.created_at desc, o.id desc limit ? offset ?", this::row, pageParameters.toArray());
        return new Page(rows, page, size, total);
    }

    /** 검색은 화면에 공개하는 작업 정보만 사용하고 토큰과 내부 서버 식별값은 제외한다. */
    private static final String SEARCH_TEXT = """
            normalize(concat_ws(' ', o.id::text, o.action, o.status, o.error_code,
                o.before_model, o.after_model, o.request->>'model', o.result->>'model', o.result->>'previousModel',
                o.result->>'sourceModel', o.result->>'selectedModel', o.result->'target'->>'selectedModel',
                o.result->'target'->>'sourceModel', o.result->'target'->>'baseModel',
                case o.action when 'MODEL_PROBE' then '설치 모델 검증' when 'MODEL_ACTIVATE' then '모델 전환'
                    when 'MODEL_ROLLBACK' then '이전 모델 복구' when 'TRAINING_START' then '학습 시작'
                    when 'TRAINING_CANCEL' then '학습 취소' else '' end,
                case o.status when 'PENDING' then '접수 대기' when 'PREPARING' then '자료 준비 중'
                    when 'TRAINING' then '학습 중' when 'VERIFYING' then '검증 중' when 'SUCCEEDED' then '완료'
                    when 'FAILED' then '실패' when 'CANCELLED' then '취소됨' when 'PASSED' then '검증 통과'
                    when 'APPLIED' then '전환 적용' when 'ROLLED_BACK' then '이전 모델 복구'
                    when 'UNKNOWN' then '결과 확인 필요' else '' end), NFKC)
            """.strip();

    public List<Map<String, Object>> historyTraining(String target) {
        return jdbc.query("""
                select o.* from public.model_management_operations o
                where o.target_key = ? and o.action = 'TRAINING_START' and o.status = 'SUCCEEDED'
                  and not exists (select 1 from public.model_versions v
                    where v.metadata->>'historySource' = 'TRAINED_MODEL_IMPORT'
                      and v.metadata->>'registrationVerified' = 'true' and v.metadata->>'trainingJobId' = o.id::text)
                order by o.created_at desc, o.id desc limit 101
                """, this::row, target);
    }

    public List<Map<String, Object>> historyTransitions(String target) {
        return jdbc.query("""
                select o.* from public.model_management_operations o
                where o.target_key = ? and ((o.action = 'MODEL_ACTIVATE' and o.status in ('APPLIED', 'UNKNOWN'))
                    or (o.action = 'MODEL_ROLLBACK' and o.status in ('ROLLED_BACK', 'UNKNOWN')))
                  and not exists (select 1 from public.model_deployments d
                    where d.metadata->>'historySource' = 'MODEL_EXECUTION' and d.metadata->>'operationId' = o.id::text)
                order by o.created_at desc, o.id desc limit 101
                """, this::row, target);
    }

    public List<Map<String, Object>> training(String target, String sourceModel, int limit) {
        return jdbc.query("""
                select * from public.model_management_operations where target_key = ? and action = 'TRAINING_START'
                and coalesce(result->'target'->>'selectedModel', request->>'model', result->'target'->>'sourceModel') = ?
                order by created_at desc, id desc limit ?
                """, this::row, target, sourceModel, limit);
    }

    public List<Map<String, Object>> activeTraining(String target) {
        return jdbc.query("""
                select * from public.model_management_operations where target_key = ? and action = 'TRAINING_START'
                and status in ('PENDING', 'PREPARING', 'TRAINING', 'VERIFYING', 'UNKNOWN')
                order by created_at desc, id desc
                """, this::row, target);
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
