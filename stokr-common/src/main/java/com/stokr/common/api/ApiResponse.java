package com.stokr.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        String correlationId,
        ApiError error
) {
    public static <T> ApiResponse<T> ok(T data, String correlationId) {
        return new ApiResponse<>(true, data, null, correlationId, null);
    }

    public static ApiResponse<Void> ok(String correlationId) {
        return new ApiResponse<>(true, null, null, correlationId, null);
    }

    public static <T> ApiResponse<T> fail(String message, String correlationId, ApiError error) {
        return new ApiResponse<>(false, null, message, correlationId, error);
    }
}
