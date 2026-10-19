package com.doenaai.analysis.controller;

import com.doenaai.analysis.service.TrainingTransferClient.CaseValidationException;
import com.doenaai.analysis.service.TrainingTransferClient.ReceiverCompatibilityException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/** 정답 원문이나 서버 예외 대신 검증 실패 코드와 사용자 안내만 반환한다. */
@RestControllerAdvice(assignableTypes = {AnalysisResultController.class, TrainingEvidenceController.class})
public class TrainingValidationAdvice {
    @ExceptionHandler(ReceiverCompatibilityException.class)
    public ResponseEntity<Map<String, Object>> receiverUpdate(ReceiverCompatibilityException error) {
        return ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("code", "RECEIVER_UPDATE_REQUIRED", "message", error.getReason()));
    }

    @ExceptionHandler(CaseValidationException.class)
    public ResponseEntity<Map<String, Object>> invalidCase(CaseValidationException error) {
        return ResponseEntity.unprocessableEntity().cacheControl(CacheControl.noStore())
                .body(Map.of("code", error.code, "field", error.field, "message", error.getReason()));
    }
}
