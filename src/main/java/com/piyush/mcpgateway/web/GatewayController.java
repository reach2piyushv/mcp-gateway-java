package com.piyush.mcpgateway.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.piyush.mcpgateway.audit.AuditLogger;
import com.piyush.mcpgateway.config.ClientConfig;
import com.piyush.mcpgateway.config.ConfigLoader;
import com.piyush.mcpgateway.config.ServerConfig;
import com.piyush.mcpgateway.discovery.ToolListCache;
import com.piyush.mcpgateway.ratelimit.TokenBucketRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The gateway's HTTP surface: POST /mcp proxies an authorized, rate-limited
 * call to a downstream MCP server; GET /discovery returns the merged,
 * TTL-cached tool catalog across every server a client is authorized for.
 * <p>
 * Downstream calls use the JDK's built-in java.net.http.HttpClient rather
 * than Spring's WebClient/RestClient, so the proxy logic depends on nothing
 * beyond what spring-boot-starter-web already pulls in.
 * <p>
 * The four-step pipeline - authenticate, rate limit, resolve+authorize target,
 * proxy+audit - is orchestrated explicitly in handleMcp() rather than as a
 * chain of Servlet filters, so it reads top-to-bottom in request order.
 */
@RestController
public class GatewayController {

    private final ConfigLoader config;
    private final TokenBucketRateLimiter rateLimiter;
    private final AuditLogger audit;
    private final ToolListCache listCache;
    private final ObjectMapper mapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final SecureRandom random = new SecureRandom();

    public GatewayController(ConfigLoader config, TokenBucketRateLimiter rateLimiter,
                              AuditLogger audit, ToolListCache listCache, ObjectMapper mapper) {
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.listCache = listCache;
        this.mapper = mapper;
    }

    @PostMapping("/mcp")
    public ResponseEntity<JsonNode> handleMcp(
            @RequestHeader(value = "x-api-key", required = false) String apiKey,
            @RequestHeader(value = "mcp-name", required = false) String mcpName,
            @RequestHeader(value = "mcp-method", required = false) String mcpMethod,
            @RequestHeader(value = "traceparent", required = false) String incomingTraceparent,
            @RequestBody JsonNode body) {

        ClientConfig client = authenticate(apiKey, body);
        checkRateLimit(client, body);
        ServerConfig target = resolveTarget(client, mcpName, mcpMethod, body);

        String traceparent = (incomingTraceparent != null && !incomingTraceparent.isBlank())
                ? incomingTraceparent
                : generateTraceparent();

        long start = System.currentTimeMillis();
        Integer upstreamStatus = null;
        String errorMessage = null;
        JsonNode result;

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(target.url()))
                    .header("content-type", "application/json")
                    .header("traceparent", traceparent)
                    .header("mcp-method", body.path("method").asText(""))
                    .header("mcp-name", target.name())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> upstream = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            upstreamStatus = upstream.statusCode();
            result = mapper.readTree(upstream.body());
        } catch (Exception e) {
            errorMessage = e.getMessage();
            ObjectNode err = mapper.createObjectNode();
            err.put("jsonrpc", "2.0");
            err.set("id", body.get("id"));
            ObjectNode errObj = err.putObject("error");
            errObj.put("code", -32003);
            errObj.put("message", "Upstream unreachable: " + target.name());
            result = err;
        }

        long latencyMs = System.currentTimeMillis() - start;

        Map<String, Object> auditFields = new LinkedHashMap<>();
        auditFields.put("clientId", client.id());
        auditFields.put("targetServer", target.name());
        auditFields.put("method", body.path("method").asText(null));
        auditFields.put("toolName", body.path("params").path("name").asText(null));
        auditFields.put("upstreamStatus", upstreamStatus);
        auditFields.put("latencyMs", latencyMs);
        auditFields.put("traceparent", traceparent);
        auditFields.put("error", errorMessage);
        audit.log(auditFields);

        HttpStatus status = errorMessage != null ? HttpStatus.BAD_GATEWAY : HttpStatus.OK;
        return ResponseEntity.status(status).header("traceparent", traceparent).body(result);
    }

    @GetMapping("/discovery")
    public ResponseEntity<Map<String, Object>> discovery(
            @RequestHeader(value = "x-api-key", required = false) String apiKey) {

        ClientConfig client = authenticate(apiKey, null);
        List<Map<String, Object>> aggregated = new ArrayList<>();

        for (ServerConfig server : config.allServers()) {
            if (!client.scopes().contains(server.name())) {
                continue;
            }
            Optional<List<Map<String, Object>>> cached = listCache.get(server.name());
            if (cached.isPresent()) {
                aggregated.add(serverEntry(server.name(), cached.get(), true, null));
                continue;
            }
            try {
                ObjectNode reqBody = mapper.createObjectNode();
                reqBody.put("jsonrpc", "2.0");
                reqBody.put("id", UUID.randomUUID().toString());
                reqBody.put("method", "tools/list");

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(server.url()))
                        .header("content-type", "application/json")
                        .header("mcp-method", "tools/list")
                        .header("mcp-name", server.name())
                        .POST(HttpRequest.BodyPublishers.ofString(reqBody.toString()))
                        .build();
                HttpResponse<String> upstream = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                JsonNode respBody = mapper.readTree(upstream.body());

                List<Map<String, Object>> tools = mapper.convertValue(
                        respBody.path("result").path("tools"),
                        new TypeReference<List<Map<String, Object>>>() {
                        });

                listCache.put(server.name(), tools, server.listCacheTtlMs());
                aggregated.add(serverEntry(server.name(), tools, false, null));
            } catch (Exception e) {
                aggregated.add(serverEntry(server.name(), List.of(), false, e.getMessage()));
            }
        }

        audit.log(Map.of("clientId", client.id(), "targetServer", "*", "method", "discovery"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client", client.id());
        response.put("servers", aggregated);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/healthz")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    private Map<String, Object> serverEntry(String serverName, List<Map<String, Object>> tools,
                                             boolean cached, String error) {
        // LinkedHashMap, not Map.of() - Map.of() throws NPE on a null value, and
        // error is legitimately null on the success path (some exception types
        // also return a null getMessage(), so this guards both directions).
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("server", serverName);
        entry.put("tools", tools);
        entry.put("cached", cached);
        if (error != null) {
            entry.put("error", error);
        }
        return entry;
    }

    // --- pipeline steps: authenticate() -> checkRateLimit() -> resolveTarget() ---

    private ClientConfig authenticate(String apiKey, JsonNode body) {
        ClientConfig client = config.clientByApiKey(apiKey);
        if (client == null) {
            throw new UnauthorizedException(idOf(body));
        }
        return client;
    }

    private void checkRateLimit(ClientConfig client, JsonNode body) {
        var rl = client.rateLimit();
        var result = rateLimiter.tryConsume(client.id(), rl.capacity(), rl.refillPerSec());
        if (!result.allowed()) {
            throw new RateLimitedException(result.retryAfterSec(), idOf(body));
        }
    }

    private ServerConfig resolveTarget(ClientConfig client, String mcpName, String mcpMethod, JsonNode body) {
        String bodyMethod = body.path("method").asText(null);

        if (mcpName == null || mcpName.isBlank()) {
            throw new BadRequestException("Missing required Mcp-Name header (target server)", idOf(body));
        }
        if (mcpMethod != null && bodyMethod != null && !mcpMethod.equals(bodyMethod)) {
            throw new BadRequestException("Mcp-Method header does not match JSON-RPC body method", idOf(body));
        }

        ServerConfig server = config.serverByName(mcpName);
        if (server == null) {
            throw new NotFoundException("Unknown target server: " + mcpName, idOf(body));
        }
        if (!client.scopes().contains(mcpName)) {
            throw new ForbiddenException(
                    "Client '" + client.id() + "' is not authorized for server '" + mcpName + "'", idOf(body));
        }
        return server;
    }

    private JsonNode idOf(JsonNode body) {
        return body != null ? body.get("id") : null;
    }

    private String generateTraceparent() {
        byte[] traceId = new byte[16];
        byte[] spanId = new byte[8];
        random.nextBytes(traceId);
        random.nextBytes(spanId);
        return "00-" + HexFormat.of().formatHex(traceId) + "-" + HexFormat.of().formatHex(spanId) + "-01";
    }
}
