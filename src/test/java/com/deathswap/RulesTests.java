package com.deathswap;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public final class RulesTests {
    private static int checks;

    public static void main(String[] args) throws IOException {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        expectFailure(() -> new MatchRules(List.of(a), 300));
        expectFailure(() -> new MatchRules(List.of(a, a), 300));
        expectFailure(() -> new MatchRules(List.of(a, b), 9));
        expectFailure(() -> new MatchRules(List.of(a, b), 86401));

        var clock = new MatchRules(List.of(a, b), 10);
        for (int i = 0; i < 199; i++) check(!clock.tick(), "Timer cannot fire early");
        check(clock.tick(), "Timer fires at 200 ticks");
        for (int i = 0; i < 30; i++) check(clock.tick() && clock.secondsRemaining() == 0, "Deferred swap stays due");
        clock.swapped();
        check(clock.secondsRemaining() == 10 && clock.swaps() == 1, "Swap resets the timer");

        var match = new MatchRules(List.of(a, b, c), 300);
        check(match.eliminate(b), "Elimination succeeds once");
        check(!match.eliminate(b) && match.contains(b), "Eliminated players stay known for respawn handling");
        check(!match.finished(), "Two players continue");
        var pair = match.destinations(new Random(1));
        check(pair.get(a).equals(c) && pair.get(c).equals(a), "Two survivors exchange destinations");
        match.eliminate(c);
        check(match.finished() && match.alive().equals(List.of(a)), "Last survivor wins");
        match.eliminate(a);
        check(match.finished() && match.alive().isEmpty() && !match.tick(), "Same-tick deaths can draw");
        expectFailure(() -> match.destinations(new Random()));

        for (int count = 2; count <= 12; count++) {
            var ids = new ArrayList<UUID>();
            for (int i = 0; i < count; i++) ids.add(UUID.randomUUID());
            var many = new MatchRules(ids, 300);
            for (int seed = 0; seed < 100; seed++) {
                var destinations = many.destinations(new Random(seed));
                check(destinations.keySet().equals(new HashSet<>(ids)), "All players receive a destination");
                check(new HashSet<>(destinations.values()).equals(new HashSet<>(ids)), "Every destination used exactly once");
                check(destinations.entrySet().stream().noneMatch(e -> e.getKey().equals(e.getValue())), "No self-swaps");
            }
        }

        var dir = Files.createTempDirectory("deathswap-rules-");
        var file = dir.resolve("spectators.txt");
        try {
            var recovery = new SpectatorRecovery(file);
            recovery.add(a);
            recovery.add(b);
            var restarted = new SpectatorRecovery(file);
            check(restarted.contains(a) && restarted.contains(b), "Recovery survives a restart");
            restarted.remove(a);
            check(!new SpectatorRecovery(file).contains(a), "Restored player removed from disk");
            restarted.remove(b);
            check(new SpectatorRecovery(file).pending().isEmpty(), "Last restoration clears recovery");
            Files.writeString(file, "invalid UUID");
            try { new SpectatorRecovery(file); throw new AssertionError("Corrupt data must fail"); }
            catch (IOException expected) { checks++; }

            var blocked = new SpectatorRecovery(file.resolve("child"));
            try { blocked.add(c); throw new AssertionError("Write must fail beneath a regular file"); }
            catch (IOException expected) { check(!blocked.contains(c), "Failed write does not authorize spectator mode"); }
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
        System.out.println("Passed " + checks + " Death Swap rule and recovery checks.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void expectFailure(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid state was accepted"); }
        catch (IllegalArgumentException | IllegalStateException expected) { checks++; }
    }
}
