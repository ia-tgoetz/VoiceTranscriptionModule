package com.wargoetz.voicetranscription.gateway.web;

/** An error that maps directly onto an HTTP status + JSON {@code {"error": message}} body. */
public class ApiException extends Exception {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
