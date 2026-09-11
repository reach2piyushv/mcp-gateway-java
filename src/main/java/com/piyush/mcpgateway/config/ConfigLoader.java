package com.piyush.mcpgateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the two config files once at startup, into simple in-memory lookup
 * maps. Kept as a plain classpath read rather than @ConfigurationProperties
 * so the config files stay easy to read and hand-edit directly.
 */
@Component
public class ConfigLoader {

    private final Map<String, ClientConfig> clientsByApiKey = new HashMap<>();
    private final Map<String, ServerConfig> serversByName = new HashMap<>();
    private final List<ServerConfig> allServers = new ArrayList<>();

    public ConfigLoader(ObjectMapper mapper) throws IOException {
        ClientsFile clientsFile;
        RegistryFile registryFile;
        try (var in = new ClassPathResource("clients.json").getInputStream()) {
            clientsFile = mapper.readValue(in, ClientsFile.class);
        }
        try (var in = new ClassPathResource("registry.json").getInputStream()) {
            registryFile = mapper.readValue(in, RegistryFile.class);
        }

        for (ClientConfig c : clientsFile.clients()) {
            clientsByApiKey.put(c.apiKey(), c);
        }
        for (ServerConfig s : registryFile.servers()) {
            serversByName.put(s.name(), s);
            allServers.add(s);
        }
    }

    public ClientConfig clientByApiKey(String apiKey) {
        return apiKey == null ? null : clientsByApiKey.get(apiKey);
    }

    public ServerConfig serverByName(String name) {
        return name == null ? null : serversByName.get(name);
    }

    public List<ServerConfig> allServers() {
        return allServers;
    }

    private record ClientsFile(List<ClientConfig> clients) {
    }

    private record RegistryFile(List<ServerConfig> servers) {
    }
}
