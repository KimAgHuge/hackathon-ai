package com.doenaai.analysis.dto;

/** 분석 화면에 공개해도 안전한 단계별 작업 내역. 서버 원본 로그나 예외 내용은 포함하지 않는다. */
public record AnalysisActivityEvent(String label, String state, String at) {
}
