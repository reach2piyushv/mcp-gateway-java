package com.piyush.mcpgateway.web;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Base type for every gateway-originated error. Each subclass corresponds
 * to one failure mode in the request pipeline: same HTTP status, same
 * JSON-RPC error code, same message shape every time it's thrown.
 */
public abstract class GatewayException extends RuntimeException {

    private final int httpStatus;
    private final int rpcCode;
    private final JsonNode requestId;

    protected GatewayException(int httpStatus, int rpcCode, String message, JsonNode requestId) {
        super(message);
        this.httpStatus = httpStatus;
        this.rpcCode = rpcCode;
        this.requestId = requestId;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public int rpcCode() {
        return rpcCode;
    }

    public JsonNode requestId() {
        return requestId;
    }
}

/** 401 — missing or invalid x-api-key. Thrown by authenticate() in GatewayController. */
class UnauthorizedException extends GatewayException {
    UnauthorizedException(JsonNode requestId) {
        super(401, -32001, "Unauthorized: missing or invalid x-api-key", requestId);
    }
}

/** 429 — token bucket exhausted. Thrown by checkRateLimit() in GatewayController. */
class RateLimitedException extends GatewayException {
    private final long retryAfterSec;

    RateLimitedException(long retryAfterSec, JsonNode requestId) {
        super(429, -32029, "Rate limit exceeded. Retry after " + retryAfterSec + "s", requestId);
        this.retryAfterSec = retryAfterSec;
    }

    long retryAfterSec() {
        return retryAfterSec;
    }
}

/** 400 — missing Mcp-Name header, or Mcp-Method disagrees with the JSON-RPC body. Thrown by resolveTarget(). */
class BadRequestException extends GatewayException {
    BadRequestException(String message, JsonNode requestId) {
        super(400, -32600, message, requestId);
    }
}

/** 404 — Mcp-Name doesn't match any registered downstream server. Thrown by resolveTarget(). */
class NotFoundException extends GatewayException {
    NotFoundException(String message, JsonNode requestId) {
        super(404, -32004, message, requestId);
    }
}

/** 403 — client authenticated fine, but isn't scoped for the requested server. Thrown by resolveTarget(). */
class ForbiddenException extends GatewayException {
    ForbiddenException(String message, JsonNode requestId) {
        super(403, -32002, message, requestId);
    }
}
