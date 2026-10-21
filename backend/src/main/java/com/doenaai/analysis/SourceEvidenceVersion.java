package com.doenaai.analysis;

import java.util.Map;

/** 소스 수집 의미가 바뀌면 버전을 올려 이전 방식의 근거가 새 분석에 섞이지 않게 한다. */
public final class SourceEvidenceVersion {
    public static final String CURRENT = "source-evidence-v2";

    private SourceEvidenceVersion() {}

    public static boolean isCurrent(Map<String, Object> sourceSummary) {
        return sourceSummary != null && CURRENT.equals(sourceSummary.get("collectorVersion"));
    }
}
