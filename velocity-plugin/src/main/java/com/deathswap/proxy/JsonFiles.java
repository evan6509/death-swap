package com.deathswap.proxy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

final class JsonFiles {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private JsonFiles() { }

    static <T> T read(Path file, Class<T> type) throws IOException {
        try {
            T value = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            if (value == null) throw new IOException("Empty JSON object: " + file);
            return value;
        } catch (RuntimeException e) {
            throw new IOException("Invalid JSON: " + file, e);
        }
    }

    static void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), file.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(value), StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // A shared volume without rename atomicity is not safe for the reset protocol.
                throw new IOException("Atomic rename is required for " + file, e);
            }
            try (FileChannel directory = FileChannel.open(file.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
