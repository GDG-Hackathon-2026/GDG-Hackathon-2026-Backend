package com.moggo._gdg.web;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.ServerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 전역 예외 핸들러. 주된 목적은 Gemini (Vertex AI) SDK 예외를 인증 실패(403) 와 구분되는 명확한
 * 502 응답으로 바꿔주는 것. 그 외는 Spring 기본 동작을 크게 건드리지 않고 최소한만 포맷 통일.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Gemini SDK 4xx/5xx 공통 처리. Spring Security 인증 403 과 혼동되지 않도록 502 로 고정. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleGeminiApi(ApiException e) {
        String upstream = e instanceof ClientException ? "4xx"
                : e instanceof ServerException ? "5xx"
                : "unknown";
        log.error("Gemini upstream error ({}): {}", upstream, e.getMessage());
        String userMessage = "LLM upstream error (" + upstream + "): " + stripUrls(e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse(
                        HttpStatus.BAD_GATEWAY.value(),
                        HttpStatus.BAD_GATEWAY.getReasonPhrase(),
                        userMessage,
                        "GEMINI_UPSTREAM"
                ));
    }

    /** 컨트롤러/서비스에서 명시적으로 던진 ResponseStatusException 을 ErrorResponse 포맷으로. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String code = switch (status) {
            case NOT_FOUND -> "NOT_FOUND";
            case BAD_REQUEST -> "VALIDATION";
            case PAYMENT_REQUIRED -> "MELTED";
            case UNAUTHORIZED, FORBIDDEN -> "AUTH";
            default -> "INTERNAL";
        };
        return ResponseEntity.status(status).body(
                new ErrorResponse(status.value(), status.getReasonPhrase(), e.getReason(), code));
    }

    /** 요청 JSON 파싱 실패 (잘못된 UTF-8 / syntax / 타입 불일치) — 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleBadJson(HttpMessageNotReadableException e) {
        log.debug("Bad request body: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                new ErrorResponse(400, "Bad Request",
                        "Invalid JSON body: " + firstLine(e.getMessage()),
                        "VALIDATION"));
    }

    /** 최후의 보루 — 예상치 못한 예외는 500 으로. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ErrorResponse(500, "Internal Server Error",
                        "unexpected error: " + e.getClass().getSimpleName(),
                        "INTERNAL"));
    }

    /** Gemini 에러 메시지에 담긴 결제 안내 URL 등을 프론트 응답에서 제거 (보안 + UX). */
    private static String stripUrls(String msg) {
        if (msg == null) return "";
        return msg.replaceAll("https?://\\S+", "[url]");
    }

    private static String firstLine(String msg) {
        if (msg == null) return "";
        int nl = msg.indexOf('\n');
        return nl < 0 ? msg : msg.substring(0, nl);
    }
}
