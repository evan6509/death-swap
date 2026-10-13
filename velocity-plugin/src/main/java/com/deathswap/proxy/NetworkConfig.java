package com.deathswap.proxy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class NetworkConfig {
    String sharedRoot = "/deathswap";
    Map<String, Backend> backendServers = new LinkedHashMap<>();
    int swapIntervalSeconds = 300;
    int statusStaleSeconds = 5;

    static NetworkConfig load(Path file) throws IOException {
        NetworkConfig config = JsonFiles.read(file, NetworkConfig.class);
        config.validate();
        return config;
    }

    void validate() throws IOException {
        if (backendServers == null || backendServers.size() < 2 || backendServers.size() > 64) {
            throw new IOException("At least two and at most 64 backends are required");
        }
        if (swapIntervalSeconds < 10 || swapIntervalSeconds > 86_400
            || statusStaleSeconds < 1 || statusStaleSeconds > 5) {
            throw new IOException("Invalid swap interval or heartbeat timeout");
        }
        var ids = new java.util.HashSet<String>();
        var paths = new java.util.HashSet<Path>();
        for (var entry : backendServers.entrySet()) {
            String name = entry.getKey();
            Backend backend = entry.getValue();
            if (name == null || !name.matches("[a-z][a-z0-9-]*") || backend == null
                || backend.serverId == null || !backend.serverId.matches("[a-z][a-z0-9-]*")
                || !ids.add(backend.serverId) || backend.statusFile == null) {
                throw new IOException("Invalid or duplicate backend identity: " + name);
            }
            Path status = Path.of(backend.statusFile).toAbsolutePath().normalize();
            if (!"status.json".equals(status.getFileName().toString()) || !paths.add(status)) {
                throw new IOException("Invalid or duplicate backend status path: " + name);
            }
        }
    }

    Path statusFile(String server) { return Path.of(backendServers.get(server).statusFile); }
    Path controlFile(String server) { return statusFile(server).resolveSibling("control.json"); }
    Path resetFile(String server) { return statusFile(server).resolveSibling("reset.request.json"); }

    static final class Backend {
        String serverId;
        String statusFile;

        Backend(String serverId, Path statusFile) {
            this.serverId = serverId;
            this.statusFile = statusFile.toString();
        }
    }
}
