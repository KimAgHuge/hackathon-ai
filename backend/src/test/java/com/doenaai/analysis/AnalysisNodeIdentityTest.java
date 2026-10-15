package com.doenaai.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalysisNodeIdentityTest {

    @Test
    void eachBackendRunHasItsOwnWorkerId() {
        var first = new AnalysisNodeIdentity(true);
        var second = new AnalysisNodeIdentity(true);

        assertThat(first.instanceId()).isNotBlank().isNotEqualTo(second.instanceId());
        first.requireLocalWorker();
    }

    @Test
    void backendWithoutWorkerRejectsAnalysisRequest() {
        var backend = new AnalysisNodeIdentity(false);

        assertThatThrownBy(backend::requireLocalWorker)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("503 SERVICE_UNAVAILABLE");
    }
}
