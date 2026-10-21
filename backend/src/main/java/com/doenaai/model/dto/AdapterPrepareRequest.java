package com.doenaai.model.dto;

import java.util.UUID;

/** 어댑터 준비는 학습 작업 식별자로 멱등 처리하며 실행 명령이나 경로를 받지 않는다. */
public record AdapterPrepareRequest(UUID operationId) { }
