package com.deathswap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Records only players placed in spectator by this mod, for reconnect/restart recovery. */
final class SpectatorRecovery {
    private final Path file;
    private final Set<UUID> pending = new LinkedHashSet<>();

    SpectatorRecovery(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) pending.add(UUID.fromString(line.strip()));
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid spectator recovery data: " + file, e);
            }
        }
    }

    Set<UUID> pending() { return Set.copyOf(pending); }
    boolean contains(UUID player) { return pending.contains(player); }

    void add(UUID player) throws IOException {
        if (!pending.add(player)) return;
        try { save(); }
        catch (IOException e) { pending.remove(player); throw e; }
    }

    void remove(UUID player) throws IOException {
        if (!pending.remove(player)) return;
        try { save(); }
        catch (IOException e) { pending.add(player); throw e; }
    }

    private void save() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "deathswap-recovery-", ".tmp");
        try {
            Files.write(temporary, pending.stream().map(UUID::toString).toList(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
