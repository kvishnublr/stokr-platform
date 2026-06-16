package com.stokr.common.exception;

import lombok.Getter;

@Getter
public class StokrException extends RuntimeException {

    private final String code;

    public StokrException(String code, String message) {
        super(message);
        this.code = code;
    }

    public StokrException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
