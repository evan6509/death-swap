package com.deathswap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

record NetworkConfig(boolean enabled, String serverId, UUID generationId,
                     int swapIntervalSeconds, int startCountdownSeconds, Path directory) {
    NetworkConfig {
        if (enabled && (serverId == null || !serverId.matches("[a-z][a-z0-9-]*")
                || generationId == null || swapIntervalSeconds < 10 || swapIntervalSeconds > 86400
                || startCountdownSeconds < 0 || startCountdownSeconds > 60 || directory == null)) {
            throw new IllegalArgumentException("Invalid Death Swap network configuration");
        }
    }

    static NetworkConfig load(Path directory) throws IOException {
        Path file = directory.resolve("network.json");
        if (!Files.exists(file)) return new NetworkConfig(false, null, null, 300, 5, directory);
        try {
            var json = NetworkFiles.read(file);
            var enabled = json.get("enabled");
            if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("network.json must specify enabled");
            }
            if (!enabled.getAsBoolean()) return new NetworkConfig(false, null, null, 300, 5, directory);
            return new NetworkConfig(true, NetworkFiles.text(json, "serverId"),
                    NetworkFiles.uuid(json, "generationId"), NetworkFiles.integer(json, "swapIntervalSeconds"),
                    NetworkFiles.integer(json, "startCountdownSeconds"), directory);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IOException("Invalid Death Swap network configuration: " + file, e);
        }
    }
}
