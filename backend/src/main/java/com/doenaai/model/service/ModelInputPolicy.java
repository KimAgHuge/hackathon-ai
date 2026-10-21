package com.doenaai.model.service;

import com.doenaai.model.dto.ModelEvaluationRequest;
import com.doenaai.model.dto.ModelVersionCreateRequest;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 경로는 NAS 기준 상대 위치로만 기록하며 비밀값과 실행 정보를 받지 않는다. */
final class ModelInputPolicy {
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(?:bearer\\s+\\S+|-----BEGIN[ A-Z]*PRIVATE KEY-----|"
            + "(?:password|passwd|secret|token|api[_ -]?key|authorization|credential|access[_ -]?key|비밀번호|비밀키|인증키|접근키)\\s*[:=]\\s*\\S+|"
            + "\\bsk-[A-Za-z0-9_-]{16,}|\\bAKIA[A-Z0-9]{16}\\b|"
            + "\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|hf_[A-Za-z0-9]{20,})\\b|"
            + "[A-Za-z][A-Za-z0-9+.-]*://[^\\s/]+@|"
            + "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|"
            + "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)");
    private static final Set<String> METRICS = Set.of("jsonCompliance", "databaseJudgment", "koreanQuality",
            "averageResponseSeconds", "sampleCount");

    private ModelInputPolicy() {}

    static ModelVersionCreateRequest version(ModelVersionCreateRequest request) {
        if (request == null) throw invalid();
        String name = text(request.modelName(), 200, request.modelId() == null);
        String description = text(request.modelDescription(), 2000, false);
        String provider = text(request.provider(), 100, false);
        String base = text(request.baseModelRef(), 500, true);
        String version = text(request.version(), 100, true);
        if (!Set.of("BASE", "LORA", "MERGED", "EXTERNAL").contains(String.valueOf(request.artifactType()))) throw invalid();
        String checksum = text(request.checksum(), 64, false);
        if (checksum != null && !checksum.matches("[A-Fa-f0-9]{64}")) throw invalid();
        return new ModelVersionCreateRequest(request.modelId(), name, description, provider, base, version,
                request.artifactType(), path(request.artifactUri()), path(request.adapterUri()), checksum,
                text(request.datasetName(), 200, false), text(request.notes(), 4000, false));
    }

    static ModelEvaluationRequest evaluation(ModelEvaluationRequest request) {
        if (request == null || !Set.of("PASSED", "FAILED").contains(String.valueOf(request.status()))) throw invalid();
        score(request.overallScore(), true);
        String notes = text(request.notes(), 4000, true);
        var metrics = new LinkedHashMap<String, Object>();
        if (request.metrics() != null) {
            if (!METRICS.containsAll(request.metrics().keySet())) throw invalid();
            request.metrics().forEach((key, value) -> {
                if (!(value instanceof Number)) throw invalid();
                BigDecimal number;
                try { number = new BigDecimal(value.toString()); }
                catch (NumberFormatException exception) { throw invalid(); }
                if (key.equals("sampleCount")) {
                    if (number.signum() <= 0 || number.stripTrailingZeros().scale() > 0) throw invalid();
                } else if (key.equals("averageResponseSeconds")) {
                    if (number.signum() < 0) throw invalid();
                } else score(number, false);
                metrics.put(key, number);
            });
        }
        return new ModelEvaluationRequest(request.status(), request.overallScore(), notes, metrics);
    }

    static boolean sensitive(String value) { return value != null && SECRET.matcher(value).find(); }

    private static void score(BigDecimal value, boolean nullable) {
        if (value == null) { if (!nullable) throw invalid(); return; }
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) throw invalid();
    }

    private static String text(String value, int max, boolean required) {
        if (value == null || value.isBlank()) { if (required) throw invalid(); return null; }
        String clean = value.strip();
        if (clean.length() > max || sensitive(clean)
                || clean.chars().anyMatch(character -> Character.isISOControl(character)
                    && character != '\n' && character != '\r' && character != '\t')) throw invalid();
        return clean;
    }

    private static String path(String value) {
        String clean = text(value, 1024, false);
        if (clean == null) return null;
        if (!clean.matches("[\\p{L}\\p{N}._ /-]+") || clean.startsWith("/") || clean.endsWith("/")) throw invalid();
        for (String part : clean.split("/", -1)) {
            if (part.isBlank() || part.strip().equals("..") || part.strip().equals(".")) throw invalid();
        }
        return clean;
    }

    private static ModelCatalogException invalid() {
        return new ModelCatalogException(HttpStatus.BAD_REQUEST,
                "모델 등록 또는 평가 입력을 확인하세요. 길이와 값의 범위를 지키고 비밀값 없이 NAS 상대경로를 입력하세요.");
    }
}
