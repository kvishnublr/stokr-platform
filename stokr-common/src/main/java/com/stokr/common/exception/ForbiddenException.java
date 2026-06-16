package com.stokr.common.exception;

public class ForbiddenException extends StokrException {

    public ForbiddenException(String message) {
        super("FORBIDDEN", message);
    }
}
