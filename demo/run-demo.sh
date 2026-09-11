#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/.."

echo "== Building (mvn package -DskipTests) =="
mvn -q package -DskipTests

echo "== Starting downstream mock servers and the gateway =="
CP="target/classes"
PORT=4001 java -cp "$CP" com.piyush.mcpgateway.downstream.WeatherServerApp &
WEATHER_PID=$!
PORT=4002 java -cp "$CP" com.piyush.mcpgateway.downstream.CustomerServerApp &
CUSTOMER_PID=$!
java -jar target/mcp-gateway-java-1.0.0.jar &
GATEWAY_PID=$!

cleanup() {
  echo ""
  echo "== Shutting down =="
  kill $WEATHER_PID $CUSTOMER_PID $GATEWAY_PID 2>/dev/null || true
}
trap cleanup EXIT

sleep 4  # Spring Boot's startup is slower than Node's - give it a moment

SUPPORT_KEY="sk-support-7f2a9c"
FRAUD_KEY="sk-fraud-4d81be"
GW="http://localhost:4000"

echo ""
echo "-------------------------------------------------------------"
echo "1) Aggregated discovery for the support agent (2 authorized servers)"
echo "-------------------------------------------------------------"
curl -s -H "x-api-key: $SUPPORT_KEY" "$GW/discovery"
echo ""

echo ""
echo "-------------------------------------------------------------"
echo "2) Support agent calls the weather tool (authorized -> routed to weather-tools)"
echo "-------------------------------------------------------------"
curl -s -X POST "$GW/mcp" \
  -H "content-type: application/json" \
  -H "x-api-key: $SUPPORT_KEY" \
  -H "mcp-method: tools/call" \
  -H "mcp-name: weather-tools" \
  -d '{"jsonrpc":"2.0","id":"1","method":"tools/call","params":{"name":"get_weather","arguments":{"city":"Dublin"}}}'
echo ""

echo ""
echo "-------------------------------------------------------------"
echo "3) Same support agent looks up a customer (one client, two backend servers, one gateway)"
echo "-------------------------------------------------------------"
curl -s -X POST "$GW/mcp" \
  -H "content-type: application/json" \
  -H "x-api-key: $SUPPORT_KEY" \
  -H "mcp-method: tools/call" \
  -H "mcp-name: customer-tools" \
  -d '{"jsonrpc":"2.0","id":"2","method":"tools/call","params":{"name":"lookup_customer","arguments":{"customerId":"cust-1002"}}}'
echo ""

echo ""
echo "-------------------------------------------------------------"
echo "4) Fraud-review agent tries the weather tool (out of scope -> 403, no upstream call made)"
echo "-------------------------------------------------------------"
curl -s -o /tmp/resp.json -w "HTTP %{http_code}\n" -X POST "$GW/mcp" \
  -H "content-type: application/json" \
  -H "x-api-key: $FRAUD_KEY" \
  -H "mcp-method: tools/call" \
  -H "mcp-name: weather-tools" \
  -d '{"jsonrpc":"2.0","id":"3","method":"tools/call","params":{"name":"get_weather","arguments":{"city":"Austin"}}}'
cat /tmp/resp.json
echo "  (note: this still consumed a rate-limit token by design - see README 'Design decisions')"
echo ""

echo ""
echo "-------------------------------------------------------------"
echo "5) Invalid API key entirely (401 Unauthorized, no token consumed)"
echo "-------------------------------------------------------------"
curl -s -o /tmp/resp.json -w "HTTP %{http_code}\n" -X POST "$GW/mcp" \
  -H "content-type: application/json" \
  -H "x-api-key: sk-invalid-key" \
  -H "mcp-method: tools/call" \
  -H "mcp-name: customer-tools" \
  -d '{"jsonrpc":"2.0","id":"4","method":"tools/call","params":{"name":"lookup_customer","arguments":{"customerId":"cust-1001"}}}'
cat /tmp/resp.json
echo ""

echo ""
echo "-------------------------------------------------------------"
echo "6) Fraud-review agent trips the rate limit (capacity=2; step 4 already spent 1 -> only 1 call left before 429)"
echo "-------------------------------------------------------------"
for i in 1 2 3; do
  echo "  call #$i:"
  curl -s -o /tmp/resp.json -w "  HTTP %{http_code}\n" -X POST "$GW/mcp" \
    -H "content-type: application/json" \
    -H "x-api-key: $FRAUD_KEY" \
    -H "mcp-method: tools/call" \
    -H "mcp-name: customer-tools" \
    -d '{"jsonrpc":"2.0","id":"5","method":"tools/call","params":{"name":"lookup_customer","arguments":{"customerId":"cust-1003"}}}'
  cat /tmp/resp.json
  echo ""
done

echo ""
echo "-------------------------------------------------------------"
echo "7) Audit log tail (logs/audit.log) - every call above, with latency + trace id"
echo "-------------------------------------------------------------"
tail -n 20 logs/audit.log

echo ""
echo "== Demo complete =="
