package com.doenaai.model.service;

import org.springframework.http.HttpStatus;

/** 요청 원문과 내부 오류를 응답에 담지 않는 모델 관리 오류다. */
public final class ModelCatalogException extends RuntimeException {
    private final HttpStatus status;

    public ModelCatalogException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() { return status; }
}
