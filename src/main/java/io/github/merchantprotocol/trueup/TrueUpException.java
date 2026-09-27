package io.github.merchantprotocol.trueup;

/** Any error the API returned, or a failure to reach it. {@link #getCode()} is the API's error code; branch on it. */
public class TrueUpException extends RuntimeException {
    private final int status;
    private final String code;
    private final String body;

    public TrueUpException(String message, int status, String code, String body) {
        super(message);
        this.status = status;
        this.code = code;
        this.body = body;
    }

    /** HTTP status (0 when the API couldn't be reached). */
    public int getStatus() { return status; }

    /** The API's error code: invalid_api_key, unsupported_file, quota_exceeded, ... */
    public String getCode() { return code; }

    /** The raw response body, if any. */
    public String getBody() { return body; }

    /** 401: missing, unknown or revoked API key. */
    public static class AuthenticationException extends TrueUpException {
        public AuthenticationException(String m, int s, String c, String b) { super(m, s, c, b); }
    }

    /** 400, 413, 415, 422: the request or the files need fixing. */
    public static class InvalidRequestException extends TrueUpException {
        public InvalidRequestException(String m, int s, String c, String b) { super(m, s, c, b); }
    }

    /** 404, 405. */
    public static class NotFoundException extends TrueUpException {
        public NotFoundException(String m, int s, String c, String b) { super(m, s, c, b); }
    }

    /** 429 rate_limited: slow down. */
    public static class RateLimitException extends TrueUpException {
        private final Double retryAfter;
        public RateLimitException(String m, int s, String c, String b, Double retryAfter) { super(m, s, c, b); this.retryAfter = retryAfter; }
        /** Seconds to wait, or null. */
        public Double getRetryAfter() { return retryAfter; }
    }

    /** 429 quota_exceeded: the team used its plan's allowance this month. Retrying won't help. */
    public static class QuotaExceededException extends TrueUpException {
        public QuotaExceededException(String m, int s, String c, String b) { super(m, s, c, b); }
    }

    /** 5xx. */
    public static class ServerException extends TrueUpException {
        public ServerException(String m, int s, String c, String b) { super(m, s, c, b); }
    }

    /** The API couldn't be reached, or took too long. */
    public static class ConnectionException extends TrueUpException {
        public ConnectionException(String m) { super(m, 0, "connection_error", null); }
    }
}
