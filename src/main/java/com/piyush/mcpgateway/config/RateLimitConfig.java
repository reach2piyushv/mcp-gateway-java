package com.piyush.mcpgateway.config;

/**
 * Per-client rate limit settings — mirrors the "rateLimit" object in clients.json.
 */
public record RateLimitConfig(int capacity, double refillPerSec) {
}
