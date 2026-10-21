package com.doenaai.model.controller;

import com.doenaai.model.service.ModelCatalogException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/** 모델 관리 요청에서 입력 원문, 비밀값, SQL 및 내부 주소를 오류 응답에 노출하지 않는다. */
@RestControllerAdvice(basePackageClasses = ModelController.class)
public class ModelExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(ModelExceptionHandler.class);
    @ExceptionHandler(ModelCatalogException.class)
    ResponseEntity<Map<String, String>> catalog(ModelCatalogException exception) {
        return error(exception.status(), exception.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> invalid(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "요청 형식이나 모델 식별자가 올바르지 않습니다.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Map<String, String>> denied(AccessDeniedException exception) {
        return error(HttpStatus.FORBIDDEN, "이 모델 관리 작업을 수행할 권한이 없습니다.");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> database(DataAccessException exception) {
        // SQL과 입력값이 예외 메시지에 섞일 수 있어 고정 코드와 종류만 기록한다.
        logger.warn("model_catalog_failed code=MODEL_STORAGE_UNAVAILABLE error_type={}", exception.getClass().getSimpleName());
        return error(HttpStatus.SERVICE_UNAVAILABLE, "모델 관리 저장소를 사용할 수 없습니다. 잠시 후 다시 시도하세요.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception exception) {
        logger.error("model_catalog_failed code=MODEL_REQUEST_FAILED error_type={}", exception.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "모델 관리 요청을 처리하지 못했습니다. 잠시 후 다시 시도하세요.");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
    }
}
