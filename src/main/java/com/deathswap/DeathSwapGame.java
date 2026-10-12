package com.deathswap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

final class DeathSwapGame {
    private final MinecraftServer server;
    private final Function<UUID, ServerPlayer> lookup;
    private final SpectatorRecovery recovery;
    private final Random random = new Random();
    private final Map<UUID, String> names = new HashMap<>();
    private final List<String> pendingAnnouncements = new ArrayList<>();
    private MatchRules match;
    private int intervalSeconds = 300;

    DeathSwapGame(MinecraftServer server) throws IOException {
        this(server, id -> server.getPlayerList().getPlayer(id),
                new SpectatorRecovery(server.getWorldPath(LevelResource.ROOT).resolve("deathswap-spectators.txt")));
    }

    // Injectable lookup also lets server GameTests use real mock ServerPlayers.
    DeathSwapGame(MinecraftServer server, Function<UUID, ServerPlayer> lookup, SpectatorRecovery recovery) {
        this.server = server;
        this.lookup = lookup;
        this.recovery = recovery;
    }

    boolean running() { return match != null; }

    void setInterval(int seconds) {
        if (running()) throw new IllegalStateException("Stop the match before changing its interval.");
        if (seconds < 10 || seconds > 86400) throw new IllegalArgumentException("Use 10 to 86400 seconds.");
        intervalSeconds = seconds;
    }

    void start(Collection<ServerPlayer> players) {
        if (running()) throw new IllegalStateException("A Death Swap match is already running.");
        for (var player : players) {
            if (!player.isAlive() || player.gameMode() != GameType.SURVIVAL) {
                throw new IllegalArgumentException("Every participant must be alive and in survival mode.");
            }
            if (player.isChangingDimension()) throw new IllegalStateException("Wait for players to finish changing dimensions.");
            if (recovery.contains(player.getUUID())) {
                throw new IllegalStateException("Spectator recovery is still pending for " + player.getName().getString());
            }
        }
        var next = new MatchRules(players.stream().map(ServerPlayer::getUUID).toList(), intervalSeconds);
        names.clear();
        for (var player : players) names.put(player.getUUID(), player.getName().getString());
        match = next;
        broadcast("Started with " + players.size() + " players. Swaps every " + intervalSeconds + " seconds.");
    }

    void eliminate(ServerPlayer player, String reason) {
        if (match == null || !match.eliminate(player.getUUID())) return;
        broadcast(player.getName().getString() + " was eliminated (" + reason + ").");
        if (player.isAlive()) spectate(player);
        // Resolve winners at the end of the tick so simultaneous deaths can draw.
    }

    void disconnected(ServerPlayer player) {
        if (match == null || !match.eliminate(player.getUUID())) return;
        // Fabric forbids packet sends during DISCONNECT. Notify after vanilla removes the player.
        pendingAnnouncements.add(player.getName().getString() + " was eliminated (disconnected).");
    }

    void joinedOrRespawned(ServerPlayer player) {
        if (match != null && match.contains(player.getUUID()) && !match.isAlive(player.getUUID())) {
            spectate(player);
        } else {
            restore(player);
        }
    }

    void tick() {
        if (match == null) return;
        for (String announcement : pendingAnnouncements) broadcast(announcement);
        pendingAnnouncements.clear();
        reconcile();
        if (finishIfNeeded()) return;
        int previousSeconds = match.secondsRemaining();
        if (match.tick()) {
            swapNow();
        } else if (match.secondsRemaining() != previousSeconds && match.secondsRemaining() <= 10) {
            broadcast("Swap in " + match.secondsRemaining() + "...");
        }
    }

    void swapNow() {
        if (match == null) throw new IllegalStateException("No Death Swap match is running.");
        reconcile();
        if (finishIfNeeded()) return;
        var players = match.alive().stream().map(lookup).toList();
        // Wait for an in-progress vanilla dimension transfer before taking snapshots.
        if (players.stream().anyMatch(ServerPlayer::isChangingDimension)) return;
        try {
            PlayerSwapper.swap(players, match.destinations(random));
            match.swapped();
            broadcast("Swapped! Next swap in " + intervalSeconds + " seconds.");
        } catch (RuntimeException e) {
            DeathSwapMod.LOGGER.error("Death Swap teleport failed; stopping the match", e);
            stop("Match stopped because a teleport failed.");
        }
    }

    private void reconcile() {
        for (UUID id : match.alive()) {
            ServerPlayer player = lookup.apply(id);
            if (player == null) {
                match.eliminate(id);
                broadcast(names.get(id) + " was eliminated (disconnected).");
            } else if (!player.isAlive() || player.gameMode() != GameType.SURVIVAL) {
                eliminate(player, player.isAlive() ? "left survival mode" : "died");
            }
        }
    }

    private boolean finishIfNeeded() {
        if (!match.finished()) return false;
        String result = match.alive().isEmpty() ? "Draw! No players remain."
                : names.get(match.alive().getFirst()) + " wins!";
        stop(result);
        return true;
    }

    void stop(String message) {
        boolean hadMatch = match != null;
        match = null;
        names.clear();
        pendingAnnouncements.clear();
        restorePlayers(recovery.pending().stream().map(lookup).filter(java.util.Objects::nonNull).toList());
        if (hadMatch) broadcast(message);
    }

    String status() {
        if (match == null) return "No match running. Swap interval: " + intervalSeconds + " seconds.";
        return match.alive().size() + " players remain: "
                + String.join(", ", match.alive().stream().map(names::get).toList())
                + ". Next swap in " + match.secondsRemaining() + " seconds. Swaps: " + match.swaps() + ".";
    }

    private void spectate(ServerPlayer player) {
        try {
            // Persist before changing player data, including for disconnects and crashes.
            recovery.add(player.getUUID());
            player.setGameMode(GameType.SPECTATOR);
        } catch (IOException e) {
            DeathSwapMod.LOGGER.error("Cannot record spectator recovery for {}; leaving game mode unchanged", player.getUUID(), e);
        }
    }

    private void restore(ServerPlayer player) {
        restorePlayers(List.of(player));
    }

    private void restorePlayers(Collection<ServerPlayer> players) {
        var restored = new ArrayList<UUID>();
        boolean changedMode = false;
        for (var player : players) {
            if (!player.isAlive() || !recovery.contains(player.getUUID())) continue;
            if (player.gameMode() == GameType.SPECTATOR) {
                player.setGameMode(GameType.SURVIVAL);
                changedMode = true;
            }
            restored.add(player.getUUID());
        }
        // Save restored modes before clearing their recovery records. Batch at match end.
        if (changedMode) server.getPlayerList().saveAll();
        for (UUID id : restored) {
            try { recovery.remove(id); }
            catch (IOException e) { DeathSwapMod.LOGGER.error("Cannot clear spectator recovery for {}", id, e); }
        }
    }

    private void broadcast(String text) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("[Death Swap] " + text), false);
    }
}
