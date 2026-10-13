package com.deathswap.proxy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class BackendStatus {
    int schemaVersion;
    String serverId;
    String generationId;
    String bootId;
    long updatedAt;
    String state;
    int playerCount;
    List<String> onlinePlayers;
    String roundId;
    List<String> participants;
    String endedRoundId;
    String commandId;
    String resetRequestId;

    boolean valid(String expectedId, long now, int staleSeconds) {
        if (schemaVersion != 1 || !expectedId.equals(serverId) || !uuid(generationId) || !uuid(bootId)
            || updatedAt > now || updatedAt < now - staleSeconds * 1_000L
            || playerCount < 0 || onlinePlayers == null || participants == null
            || onlinePlayers.size() != playerCount || !uniqueUuids(onlinePlayers) || !uniqueUuids(participants)
            || state == null || !Set.of("ready", "waiting", "prepared", "running", "ended", "resetting", "stopped").contains(state)
            || !nullableUuid(roundId) || !nullableUuid(endedRoundId) || !nullableUuid(commandId) || !nullableUuid(resetRequestId)) {
            return false;
        }
        return !"ready".equals(state) || playerCount == 0;
    }

    private static boolean uniqueUuids(List<String> values) {
        Set<String> unique = new HashSet<>();
        for (String value : values) if (!uuid(value) || !unique.add(value)) return false;
        return true;
    }

    static boolean uuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException e) { return false; }
    }

    static boolean nullableUuid(String value) { return value == null || uuid(value); }
}
