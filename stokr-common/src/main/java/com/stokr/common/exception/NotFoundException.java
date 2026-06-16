package com.stokr.common.exception;

public class NotFoundException extends StokrException {

    public NotFoundException(String message) {
        super("NOT_FOUND", message);
    }
}
