package com.deathswap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PlayerSwapper {
    private record Location(ServerLevel level, Vec3 position, float yaw, float pitch) {
        static Location capture(ServerPlayer player) {
            return new Location(player.level(), player.position(), player.getYRot(), player.getXRot());
        }
        boolean move(ServerPlayer player) {
            player.stopRiding();
            boolean moved = player.teleportTo(level, position.x, position.y, position.z,
                    Set.of(), yaw, pitch, true);
            if (moved) {
                player.setDeltaMovement(Vec3.ZERO);
                player.resetFallDistance();
            }
            return moved;
        }
    }

    static void swap(Collection<ServerPlayer> players, Map<UUID, UUID> destinations) {
        Map<UUID, ServerPlayer> byId = new LinkedHashMap<>();
        for (var player : players) {
            byId.put(player.getUUID(), player);
        }
        if (players.size() < 2 || byId.size() != players.size()
                || !destinations.keySet().equals(byId.keySet())
                || !Set.copyOf(destinations.values()).equals(byId.keySet())) {
            throw new IllegalArgumentException("Swap must include every participant exactly once.");
        }
        for (var entry : destinations.entrySet()) {
            if (entry.getKey().equals(entry.getValue())) {
                throw new IllegalArgumentException("A player cannot swap with themselves.");
            }
        }
        // Waking can move a player beside their bed. Finish all wake-ups before taking snapshots.
        for (var player : players) {
            if (player.isSleeping()) player.stopSleepInBed(true, true);
        }
        Map<UUID, Location> locations = new LinkedHashMap<>();
        for (var player : players) locations.put(player.getUUID(), Location.capture(player));
        // Capture every location before teleporting anyone, including across dimensions.
        try {
            for (var entry : destinations.entrySet()) {
                if (!locations.get(entry.getValue()).move(byId.get(entry.getKey()))) {
                    throw new IllegalStateException("Minecraft rejected a player teleport.");
                }
            }
        } catch (RuntimeException failure) {
            // Avoid leaving half the group swapped if a teleport is rejected.
            for (var player : players) {
                try {
                    if (!locations.get(player.getUUID()).move(player)) {
                        DeathSwapMod.LOGGER.error("Could not roll back Death Swap teleport for {}", player.getUUID());
                    }
                } catch (RuntimeException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }
}
