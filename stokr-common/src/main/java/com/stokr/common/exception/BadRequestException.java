package com.stokr.common.exception;

public class BadRequestException extends StokrException {

    public BadRequestException(String message) {
        super("VALIDATION", message);
    }
}
