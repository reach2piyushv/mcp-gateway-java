package com.piyush.mcpgateway.downstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

/**
 * Standalone mock downstream MCP server — customer/risk lookup, styled after
 * DM-style fraud data so the gateway's authorization story (a fraud-review
 * agent vs. a support agent) mirrors a real production concern.
 */
public class CustomerServerApp {

    private record CustomerRecord(String name, int accountAgeDays, int riskScore, List<String> flags) {}

    private static final Map<String, CustomerRecord> MOCK_CUSTOMERS = Map.of(
            "cust-1001", new CustomerRecord("A. Rivera", 812, 12, List.of()),
            "cust-1002", new CustomerRecord("J. Okafor", 4, 78, List.of("new_account", "velocity_spike")),
            "cust-1003", new CustomerRecord("S. Kapoor", 190, 34, List.of("device_mismatch"))
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "4002"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", CustomerServerApp::handle);
        server.setExecutor(null);
        server.start();
        System.out.println("[customer-server] listening on :" + port);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        JsonNode body = MAPPER.readTree(exchange.getRequestBody());
        String method = body.path("method").asText(null);
        JsonNode id = body.get("id");

        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);

        if ("tools/list".equals(method)) {
            ObjectNode result = response.putObject("result");
            ArrayNode tools = result.putArray("tools");
            ObjectNode tool = tools.addObject();
            tool.put("name", "lookup_customer");
            tool.put("description", "Look up a customer profile and mock fraud risk score by customer id");
            ObjectNode schema = tool.putObject("inputSchema");
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            props.putObject("customerId").put("type", "string");
            schema.putArray("required").add("customerId");
        } else if ("tools/call".equals(method)) {
            JsonNode params = body.path("params");
            String toolName = params.path("name").asText(null);
            if (!"lookup_customer".equals(toolName)) {
                ObjectNode error = response.putObject("error");
                error.put("code", -32601);
                error.put("message", "Unknown tool: " + toolName);
            } else {
                String customerId = params.path("arguments").path("customerId").asText(null);
                CustomerRecord record = customerId != null ? MOCK_CUSTOMERS.get(customerId) : null;
                String text = record != null
                        ? record.name() + " | account age: " + record.accountAgeDays() + "d | risk score: "
                            + record.riskScore() + " | flags: "
                            + (record.flags().isEmpty() ? "none" : String.join(", ", record.flags()))
                        : "No customer found for id " + customerId;
                ObjectNode result = response.putObject("result");
                ArrayNode content = result.putArray("content");
                ObjectNode block = content.addObject();
                block.put("type", "text");
                block.put("text", text);
            }
        } else {
            ObjectNode error = response.putObject("error");
            error.put("code", -32601);
            error.put("message", "Unknown method: " + method);
        }

        byte[] bytes = MAPPER.writeValueAsBytes(response);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
