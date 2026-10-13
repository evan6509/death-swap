package com.deathswap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

final class NetworkFiles {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    static JsonObject read(Path file) throws IOException {
        if (Files.size(file) > 65536) throw new IOException("Network file exceeds 64 KiB: " + file);
        try {
            var value = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!value.isJsonObject()) throw new IOException("Expected a JSON object: " + file);
            return value.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Invalid network JSON: " + file, e);
        }
    }

    static void write(Path file, JsonObject value) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".deathswap-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(value) + "\n", StandardCharsets.UTF_8);
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) { output.force(true); }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Death Swap network requires atomic rename: " + file, e);
            }
            try (var directory = FileChannel.open(file.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    static String text(JsonObject value, String key) {
        var entry = value.get(key);
        if (entry == null || !entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing string: " + key);
        }
        return entry.getAsString();
    }

    static UUID uuid(JsonObject value, String key) { return UUID.fromString(text(value, key)); }

    static int integer(JsonObject value, String key) {
        var entry = value.get(key);
        if (entry == null || !entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing integer: " + key);
        }
        return entry.getAsBigDecimal().intValueExact();
    }

    static Set<UUID> roster(JsonObject value) {
        var entry = value.get("participants");
        if (entry == null || !entry.isJsonArray() || entry.getAsJsonArray().size() > 1024) {
            throw new IllegalArgumentException("Invalid participants list");
        }
        Set<UUID> result = new LinkedHashSet<>();
        for (var participant : entry.getAsJsonArray()) {
            if (!participant.isJsonPrimitive() || !participant.getAsJsonPrimitive().isString()
                    || !result.add(UUID.fromString(participant.getAsString()))) {
                throw new IllegalArgumentException("Invalid or duplicate participant UUID");
            }
        }
        return result;
    }
}
