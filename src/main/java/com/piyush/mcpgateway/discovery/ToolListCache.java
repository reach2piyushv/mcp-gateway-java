package com.piyush.mcpgateway.discovery;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches each downstream server's tools/list response for listCacheTtlMs,
 * mirroring the ttlMs/cacheScope pattern introduced in the MCP 2026-07-28
 * spec so the gateway doesn't re-fetch tool lists from every downstream on
 * every /discovery call.
 */
@Component
public class ToolListCache {

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public Optional<List<Map<String, Object>>> get(String serverName) {
        Entry entry = cache.get(serverName);
        if (entry != null && entry.expiresAtMillis > System.currentTimeMillis()) {
            return Optional.of(entry.tools);
        }
        return Optional.empty();
    }

    public void put(String serverName, List<Map<String, Object>> tools, long ttlMillis) {
        cache.put(serverName, new Entry(tools, System.currentTimeMillis() + ttlMillis));
    }

    private record Entry(List<Map<String, Object>> tools, long expiresAtMillis) {
    }
}
