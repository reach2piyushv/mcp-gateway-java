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
import java.util.Map;

/**
 * Standalone mock downstream MCP server — deliberately dependency-free (JDK's
 * built-in HttpServer, no Spring) since it exists purely as a test fixture
 * for the gateway demo, not production code. The real Java-stack showcase is
 * GatewayApplication and the classes under web/, ratelimit/, audit/, and
 * discovery/.
 */
public class WeatherServerApp {

    private record WeatherInfo(double tempC, String condition) {}

    private static final Map<String, WeatherInfo> MOCK_WEATHER = Map.of(
            "dublin", new WeatherInfo(17, "overcast"),
            "austin", new WeatherInfo(34, "clear"),
            "bangalore", new WeatherInfo(27, "light rain")
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "4001"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", WeatherServerApp::handle);
        server.setExecutor(null);
        server.start();
        System.out.println("[weather-server] listening on :" + port);
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
            tool.put("name", "get_weather");
            tool.put("description", "Get current mock weather for a city");
            ObjectNode schema = tool.putObject("inputSchema");
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            props.putObject("city").put("type", "string");
            schema.putArray("required").add("city");
        } else if ("tools/call".equals(method)) {
            JsonNode params = body.path("params");
            String toolName = params.path("name").asText(null);
            if (!"get_weather".equals(toolName)) {
                ObjectNode error = response.putObject("error");
                error.put("code", -32601);
                error.put("message", "Unknown tool: " + toolName);
            } else {
                String city = params.path("arguments").path("city").asText("");
                WeatherInfo info = MOCK_WEATHER.get(city.toLowerCase());
                String text = info != null
                        ? city + ": " + info.tempC() + "\u00b0C, " + info.condition()
                        : city + ": 20\u00b0C, unknown - not in mock dataset";
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
