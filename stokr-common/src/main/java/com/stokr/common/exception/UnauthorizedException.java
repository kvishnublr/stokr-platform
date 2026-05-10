package com.stokr.common.exception;

public class UnauthorizedException extends StokrException {

    public UnauthorizedException(String message) {
        super("UNAUTHORIZED", message);
    }
}
