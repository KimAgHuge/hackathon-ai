package com.doenaai.model.repository;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 이력 조회가 전체 목록을 읽지 않고 같은 조건의 개수와 페이지를 조회하는지 검사한다. */
class ModelOperationRepositoryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ModelOperationRepository repository = new ModelOperationRepository(jdbc, JsonMapper.builder().build());

    @Test void filtersUseTheSameParameterizedConditionAndStableLimitedOrdering() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(135L);
        var result = repository.page(12, 10, "qwen%_!'", "SUCCEEDED");
        assertThat(result.totalCount()).isEqualTo(135);
        assertThat(result.page()).isEqualTo(12);
        var calls = mockingDetails(jdbc).getInvocations().stream().toList();
        assertThat(calls).hasSize(2);
        String count = calls.get(0).getArgument(0);
        String list = calls.get(1).getArgument(0);
        assertThat(count.substring(count.indexOf(" where")))
                .isEqualTo(list.substring(list.indexOf(" where"), list.indexOf(" order by")));
        assertThat(list).contains("order by o.created_at desc, o.id desc limit ? offset ?", "ilike ? escape '!'",
                "설치 모델 검증", "학습 시작", "전환 적용", "결과 확인 필요")
                .doesNotContain("qwen%_!", "probeTokenHash", "target_key", "request::text");
        verify(jdbc).queryForObject(anyString(), eq(Long.class), eq("SUCCEEDED"), eq("%qwen!%!_!!'%"));
        verify(jdbc).query(anyString(), any(RowMapper.class), eq("SUCCEEDED"), eq("%qwen!%!_!!'%"), eq(10), eq(120L));
    }

    @Test void pagePastTheEndUsesTheLastPageWithoutAnUnboundedRead() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(21L);
        assertThat(repository.page(Integer.MAX_VALUE, 10, "", "").page()).isEqualTo(2);
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(10), eq(20L));
    }

    @Test void everyDistinctSearchTermGetsItsOwnAndConditionAndBoundValue() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        repository.page(0, 10, "qwen 학습 qwen", "");
        verify(jdbc).queryForObject(anyString(), eq(Long.class), eq("%qwen%"), eq("%학습%"));
        verify(jdbc).query(anyString(), any(RowMapper.class), eq("%qwen%"), eq("%학습%"), eq(10), eq(0L));
    }

    @Test void emptySearchResetsThePageAndSkipsSearchConditions() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        var result = repository.page(8, 10, "", "");
        assertThat(result.page()).isZero();
        assertThat(result.totalCount()).isZero();
        verify(jdbc).queryForObject(eq("select count(*) from public.model_management_operations o where true"),
                eq(Long.class), aryEq(new Object[0]));
        verify(jdbc).query(eq("select o.* from public.model_management_operations o where true order by o.created_at desc, o.id desc limit ? offset ?"),
                any(RowMapper.class), eq(10), eq(0L));
    }

    @Test void normalizationAndRepeatableReadKeepCountAndPageConsistent() throws Exception {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        repository.page(0, 10, "Ｑｗｅｎ", "");
        verify(jdbc).queryForObject(contains("normalize(concat_ws("), eq(Long.class), eq("%Qwen%"));
        var transaction = ModelOperationRepository.class.getMethod("page", int.class, int.class, String.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    @Test void historyRepairQueriesOnlyMissingCompletedRecordsForTheCurrentTarget() {
        repository.historyTraining("training-target");
        repository.historyTransitions("inference-target");
        verify(jdbc).query(argThat(sql -> sql.contains("o.target_key = ?") && sql.contains("o.status = 'SUCCEEDED'")
                        && sql.contains("registrationVerified") && sql.contains("not exists") && sql.contains("limit 101")),
                any(RowMapper.class), eq("training-target"));
        verify(jdbc).query(argThat(sql -> sql.contains("o.target_key = ?") && sql.contains("'APPLIED', 'UNKNOWN'")
                        && sql.contains("'ROLLED_BACK', 'UNKNOWN'") && sql.contains("operationId") && sql.contains("not exists") && sql.contains("limit 101")),
                any(RowMapper.class), eq("inference-target"));
    }
}
