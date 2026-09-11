package com.piyush.mcpgateway.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Appends one JSON object per line to logs/audit.log. Every field here is
 * deliberately something a security or compliance reviewer would ask for
 * after the fact: who, what, where, when, how long, what happened.
 * <p>
 * Logs metadata only, never full tool payloads — given one downstream server
 * in this project's demo setup handles customer-style data, logging
 * arguments/results verbatim by default would create a second, uncontrolled
 * copy of sensitive data on disk.
 */
@Component
public class AuditLogger {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path logPath = Path.of("logs", "audit.log");
    private final Object writeLock = new Object();

    public AuditLogger() throws IOException {
        Path parent = logPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(logPath)) {
            Files.createFile(logPath);
        }
    }

    public void log(Map<String, Object> fields) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("ts", Instant.now().toString());
        record.putAll(fields);
        try {
            String line = mapper.writeValueAsString(record) + System.lineSeparator();
            synchronized (writeLock) {
                Files.writeString(logPath, line, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            // Never fail the request over audit logging - surface to stderr instead.
            System.err.println("Failed to write audit log: " + e.getMessage());
        }
    }
}
