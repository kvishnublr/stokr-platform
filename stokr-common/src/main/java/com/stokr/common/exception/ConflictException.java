package com.stokr.common.exception;

public class ConflictException extends StokrException {

    public ConflictException(String message) {
        super("CONFLICT", message);
    }
}
