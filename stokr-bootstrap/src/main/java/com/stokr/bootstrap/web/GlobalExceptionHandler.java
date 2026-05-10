package com.stokr.bootstrap.web;

import com.stokr.common.api.ApiError;
import com.stokr.common.api.ApiResponse;
import com.stokr.common.correlation.CorrelationIdHolder;
import com.stokr.common.exception.StokrException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(StokrException.class)
    public ResponseEntity<ApiResponse<Void>> handleStokr(StokrException ex) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        if ("NOT_FOUND".equals(ex.getCode())) {
            status = HttpStatus.NOT_FOUND;
        } else if ("UNAUTHORIZED".equals(ex.getCode())) {
            status = HttpStatus.UNAUTHORIZED;
        } else if ("CONFLICT".equals(ex.getCode())) {
            status = HttpStatus.CONFLICT;
        } else if ("FORBIDDEN".equals(ex.getCode())) {
            status = HttpStatus.FORBIDDEN;
        }
        String cid = CorrelationIdHolder.get();
        return ResponseEntity.status(status)
                .body(ApiResponse.fail(ex.getMessage(), cid, new ApiError(ex.getCode(), ex.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fe = ex.getBindingResult().getFieldError();
        String msg = fe == null ? "Validation failed" : fe.getField() + ": " + fe.getDefaultMessage();
        String cid = CorrelationIdHolder.get();
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(msg, cid, new ApiError("VALIDATION", msg)));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        String cid = CorrelationIdHolder.get();
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.fail("Forbidden", cid, new ApiError("FORBIDDEN", ex.getMessage())));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        String cid = CorrelationIdHolder.get();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail("Unauthorized", cid, new ApiError("UNAUTHORIZED", ex.getMessage())));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFoundResource(NoResourceFoundException ex) {
        String cid = CorrelationIdHolder.get();
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail("Not found", cid, new ApiError("NOT_FOUND", ex.getResourcePath())));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation", ex);
        String cid = CorrelationIdHolder.get();
        String msg = "Resource conflict";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.fail(msg, cid, new ApiError("CONFLICT", msg)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled error", ex);
        String cid = CorrelationIdHolder.get();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("Internal server error", cid, new ApiError("INTERNAL", ex.getMessage())));
    }
}
