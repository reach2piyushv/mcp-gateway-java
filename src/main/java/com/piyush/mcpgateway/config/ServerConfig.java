package com.piyush.mcpgateway.config;

/**
 * A registered downstream MCP tool server — mirrors one entry in registry.json's
 * "servers" array. listCacheTtlMs controls how long this server's tools/list
 * response is cached by ToolListCache before being re-fetched.
 */
public record ServerConfig(
        String name,
        String url,
        String description,
        long listCacheTtlMs
) {
}
