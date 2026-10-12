package com.deathswap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Server-thread match state; contains no Minecraft dependencies. */
final class MatchRules {
    private final Set<UUID> participants;
    private final Set<UUID> alive;
    private final int intervalTicks;
    private int remainingTicks;
    private int swaps;

    MatchRules(Collection<UUID> players, int intervalSeconds) {
        participants = new LinkedHashSet<>(players);
        if (participants.size() != players.size() || participants.size() < 2) {
            throw new IllegalArgumentException("Choose at least two distinct players.");
        }
        if (intervalSeconds < 10 || intervalSeconds > 86400) {
            throw new IllegalArgumentException("Swap interval must be 10 to 86400 seconds.");
        }
        alive = new LinkedHashSet<>(participants);
        intervalTicks = intervalSeconds * 20;
        remainingTicks = intervalTicks;
    }

    boolean eliminate(UUID player) { return alive.remove(player); }
    boolean contains(UUID player) { return participants.contains(player); }
    boolean isAlive(UUID player) { return alive.contains(player); }
    boolean finished() { return alive.size() <= 1; }
    List<UUID> alive() { return List.copyOf(alive); }
    int secondsRemaining() { return (remainingTicks + 19) / 20; }
    int swaps() { return swaps; }

    boolean tick() {
        if (finished()) return false;
        remainingTicks = Math.max(0, remainingTicks - 1);
        return remainingTicks == 0;
    }

    void swapped() {
        swaps++;
        remainingTicks = intervalTicks;
    }

    /** One shuffled cycle gives everyone exactly one other player's location. */
    Map<UUID, UUID> destinations(Random random) {
        var cycle = new ArrayList<>(alive);
        if (cycle.size() < 2) throw new IllegalStateException("Not enough players to swap.");
        Collections.shuffle(cycle, random);
        Map<UUID, UUID> result = new LinkedHashMap<>();
        for (int i = 0; i < cycle.size(); i++) {
            result.put(cycle.get(i), cycle.get((i + 1) % cycle.size()));
        }
        return result;
    }
}
