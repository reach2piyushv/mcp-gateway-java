package com.piyush.mcpgateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Central place every GatewayException gets turned into a JSON-RPC error body
 * plus the matching HTTP status, so error handling doesn't get scattered
 * across every controller method as inline status/body construction.
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private final ObjectMapper mapper = new ObjectMapper();

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ObjectNode> handle(GatewayException ex) {
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.set("id", ex.requestId() != null ? ex.requestId() : NullNode.getInstance());
        ObjectNode error = body.putObject("error");
        error.put("code", ex.rpcCode());
        error.put("message", ex.getMessage());

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.httpStatus());
        if (ex instanceof RateLimitedException rle) {
            builder.header("Retry-After", String.valueOf(rle.retryAfterSec()));
        }
        return builder.body(body);
    }
}
