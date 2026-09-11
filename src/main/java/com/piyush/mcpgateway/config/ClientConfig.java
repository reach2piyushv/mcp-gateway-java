package com.piyush.mcpgateway.config;

import java.util.List;

/**
 * A registered gateway client — mirrors one entry in clients.json's "clients" array.
 * scopes() lists the server names (from registry.json) this client is authorized to reach.
 */
public record ClientConfig(
        String id,
        String apiKey,
        String description,
        List<String> scopes,
        RateLimitConfig rateLimit
) {
}
