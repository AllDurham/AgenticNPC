package com.agenticnpc.gateway.model;

public record SanitizeResult(
    boolean accepted,
    String  value,
    String  rejectReason
) {
    public static SanitizeResult accept(String value) {
        return new SanitizeResult(true, value, null);
    }

    public static SanitizeResult reject(String reason) {
        return new SanitizeResult(false, null, reason);
    }
}
