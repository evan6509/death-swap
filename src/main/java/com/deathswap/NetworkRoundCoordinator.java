package com.deathswap;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Optional shared-volume round transport, confined to the Minecraft server thread. */
final class NetworkRoundCoordinator {
    private final MinecraftServer server;
    private final DeathSwapGame game;
    private final NetworkConfig config;
    private final Supplier<List<ServerPlayer>> online;
    private final UUID bootId = UUID.randomUUID();
    private final Set<UUID> participants = new LinkedHashSet<>();
    private volatile String state = "ready";
    private UUID roundId;
    private UUID endedRoundId;
    private UUID commandId;
    private UUID resetRequestId;
    private int swapIntervalSeconds;
    private int countdownTicks = -1;
    private int ticks;
    private volatile boolean failed;

    NetworkRoundCoordinator(MinecraftServer server, DeathSwapGame game, NetworkConfig config) throws IOException {
        this(server, game, config, () -> List.copyOf(server.getPlayerList().getPlayers()));
    }

    NetworkRoundCoordinator(MinecraftServer server, DeathSwapGame game, NetworkConfig config,
                            Supplier<List<ServerPlayer>> online) throws IOException {
        if (!config.enabled()) throw new IllegalArgumentException("Network coordinator requires enabled config");
        this.server = server;
        this.game = game;
        this.config = config;
        this.online = online;
        swapIntervalSeconds = config.swapIntervalSeconds();
        game.setManagedNetwork(true);
        var usedFile = config.directory().resolve("used.json");
        if (Files.exists(usedFile)) {
            try {
                var saved = NetworkFiles.read(usedFile);
                requireIdentity(saved);
                state = NetworkFiles.text(saved, "state");
                if (!Set.of("waiting", "prepared", "running", "ended", "resetting").contains(state)) {
                    throw new IllegalArgumentException("Invalid persisted network state");
                }
                roundId = optionalUuid(saved, "roundId");
                endedRoundId = optionalUuid(saved, "endedRoundId");
                commandId = optionalUuid(saved, "commandId");
                resetRequestId = optionalUuid(saved, "resetRequestId");
                participants.addAll(NetworkFiles.roster(saved));
                swapIntervalSeconds = NetworkFiles.integer(saved, "swapIntervalSeconds");
                if (roundId == null || swapIntervalSeconds < 10 || swapIntervalSeconds > 86400
                        || state.equals("resetting") && resetRequestId == null) {
                    throw new IllegalArgumentException("Incomplete persisted round identity");
                }
                if (state.equals("running")) {
                    state = "ended";
                    endedRoundId = roundId;
                    persistUsed();
                }
            } catch (IllegalArgumentException | ArithmeticException e) {
                throw new IOException("Invalid persisted network round: " + usedFile, e);
            }
        }
        game.setPaused(!state.equals("running"));
        writeStatus();
    }

    String state() { return state; }
    boolean rejectsJoins() { return failed || state.equals("resetting") || state.equals("stopped"); }

    boolean onDeath(ServerPlayer player) {
        if (failed || !state.equals("running") || !game.isActiveParticipant(player.getUUID())) return false;
        state = "ended";
        endedRoundId = roundId;
        countdownTicks = -1;
        game.setPaused(true);
        try {
            persistUsed();
            writeStatus();
            announce(player.getName().getString() + " died. Round ended; waiting for a fresh server.");
        } catch (IOException e) { fail(e); }
        return true;
    }

    void joinedOrRespawned(ServerPlayer player) {
        if (rejectsJoins()) {
            player.connection.disconnect(Component.literal("This world is resetting. Reconnect through the proxy."));
            return;
        }
        try { readControl(); }
        catch (IOException e) { fail(e); }
        if (state.equals("waiting")) {
            game.restore(player);
            if (player.isAlive()) player.setGameMode(GameType.SURVIVAL);
        } else if (!state.equals("running") || !participants.contains(player.getUUID())) {
            if (player.isAlive()) game.spectate(player);
        } else {
            game.restore(player);
        }
    }

    void disconnected(ServerPlayer player) {
        // No packets or file writes from Fabric's DISCONNECT callback.
        if (state.equals("running") && participants.contains(player.getUUID())) game.setPaused(true);
    }

    void tick() {
        if (failed || state.equals("stopped")) return;
        ticks++;
        try {
            readControl();
            readReset();
            switch (state) {
                case "ready" -> {
                    game.setPaused(true);
                    holdPlayers();
                    game.setManagedStatus("Fresh world ready; waiting for the proxy to assign a round.");
                }
                case "waiting" -> tickLobby();
                case "prepared" -> tickPrepared();
                case "running" -> tickRunning();
                case "ended" -> {
                    game.setPaused(true);
                    holdPlayers();
                    game.setManagedStatus("Round ended; waiting for a fresh server. Everyone moves together.");
                }
                case "resetting" -> {
                    game.setPaused(true);
                    game.setManagedStatus("World resetting.");
                }
                default -> throw new IOException("Unknown network state: " + state);
            }
            // Control/death transitions also publish immediately where acknowledgment matters.
            if (ticks % 20 == 0 || ticks == 1) writeStatus();
        } catch (IOException | RuntimeException e) { fail(e); }
    }

    private void tickLobby() throws IOException {
        game.setPaused(true);
        var players = livingPlayers();
        for (var player : players) {
            game.restore(player);
            player.setGameMode(GameType.SURVIVAL);
        }
        if (players.size() < 2) {
            countdownTicks = -1;
            game.setManagedStatus("Waiting for at least two players to start a fresh Death Swap round.");
            return;
        }
        if (countdownFinished()) {
            participants.clear();
            players.forEach(p -> participants.add(p.getUUID()));
            start(players);
        }
    }

    private void tickPrepared() throws IOException {
        game.setPaused(true);
        var players = holdPlayers();
        Set<UUID> arrived = new LinkedHashSet<>();
        players.forEach(p -> arrived.add(p.getUUID()));
        if (!arrived.containsAll(participants)) {
            countdownTicks = -1;
            game.setManagedStatus("Waiting for all players to arrive on the fresh server.");
            return;
        }
        if (participants.size() < 2) {
            state = "waiting";
            persistUsed();
            writeStatus();
            return;
        }
        if (countdownFinished()) {
            var roster = players.stream().filter(p -> participants.contains(p.getUUID())).toList();
            for (var player : roster) {
                game.restore(player);
                player.setGameMode(GameType.SURVIVAL);
            }
            start(roster);
        }
    }

    private boolean countdownFinished() {
        if (countdownTicks < 0) countdownTicks = config.startCountdownSeconds() * 20;
        if (countdownTicks == 0) return true;
        if (countdownTicks % 20 == 0) {
            announce("Fresh Death Swap round starts in " + countdownTicks / 20 + "...");
        }
        game.setManagedStatus("Fresh round starts in " + (countdownTicks + 19) / 20 + " seconds.");
        countdownTicks--;
        return false;
    }

    private void start(List<ServerPlayer> players) throws IOException {
        game.setInterval(swapIntervalSeconds);
        game.start(players);
        state = "running";
        game.setManagedStatus(null);
        persistUsed();
        writeStatus();
    }

    private void tickRunning() throws IOException {
        if (!game.running()) {
            state = "ended";
            endedRoundId = roundId;
            game.setPaused(true);
            persistUsed();
            writeStatus();
            return;
        }
        var players = online.get();
        Set<UUID> present = new LinkedHashSet<>();
        players.forEach(p -> present.add(p.getUUID()));
        if (!present.containsAll(participants)) {
            game.setPaused(true);
            for (var player : players) if (player.isAlive()) game.spectate(player);
            game.setManagedStatus("Round paused; waiting for its disconnected players to reconnect.");
        } else {
            if (game.paused()) {
                for (var player : players) if (participants.contains(player.getUUID())) game.restore(player);
            }
            for (var player : players) {
                if (!participants.contains(player.getUUID()) && player.isAlive()) game.spectate(player);
            }
            game.setPaused(false);
            game.setManagedStatus(null);
        }
    }

    private List<ServerPlayer> livingPlayers() {
        return online.get().stream().map(this::respawn).filter(ServerPlayer::isAlive).toList();
    }

    private List<ServerPlayer> holdPlayers() {
        var players = livingPlayers();
        for (var player : players) game.spectate(player);
        return players;
    }

    private ServerPlayer respawn(ServerPlayer player) {
        if (player.isAlive()) return player;
        return server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
    }

    private void readControl() throws IOException {
        var file = config.directory().resolve("control.json");
        if (!Files.exists(file)) return;
        try {
            var control = NetworkFiles.read(file);
            requireIdentity(control);
            UUID id = NetworkFiles.uuid(control, "commandId");
            if (id.equals(commandId)) return;
            UUID nextRound = NetworkFiles.uuid(control, "roundId");
            String action = NetworkFiles.text(control, "action");
            int interval = NetworkFiles.integer(control, "swapIntervalSeconds");
            if (interval < 10 || interval > 86400) throw new IllegalArgumentException("Invalid swap interval");
            Set<UUID> nextParticipants = NetworkFiles.roster(control);
            if (action.equals("activate") && state.equals("ready")) {
                state = "waiting";
                roundId = nextRound;
                participants.clear();
            } else if (action.equals("prepare") && (state.equals("ready")
                    || state.equals("prepared") && nextRound.equals(roundId))) {
                state = "prepared";
                roundId = nextRound;
                participants.clear();
                participants.addAll(nextParticipants);
            } else return;
            swapIntervalSeconds = interval;
            commandId = id;
            endedRoundId = null;
            countdownTicks = -1;
            game.setPaused(true);
            persistUsed();
            writeStatus();
        } catch (IllegalArgumentException | ArithmeticException e) {
            // Stale generations are expected after reset; leave the current world alone.
            DeathSwapMod.LOGGER.debug("Ignoring invalid or stale Death Swap control: {}", e.getMessage());
        }
    }

    private void readReset() throws IOException {
        var file = config.directory().resolve("reset.request.json");
        if (!Files.exists(file) || !state.equals("ended") || !online.get().isEmpty()) return;
        try {
            var request = NetworkFiles.read(file);
            requireIdentity(request);
            resetRequestId = NetworkFiles.uuid(request, "requestId");
            state = "resetting";
            game.setPaused(true);
            persistUsed();
            writeStatus();
        } catch (IllegalArgumentException | ArithmeticException e) {
            DeathSwapMod.LOGGER.debug("Ignoring invalid or stale Death Swap reset: {}", e.getMessage());
        }
    }

    private void requireIdentity(JsonObject value) {
        if (NetworkFiles.integer(value, "schemaVersion") != 1
                || !NetworkFiles.text(value, "serverId").equals(config.serverId())
                || !NetworkFiles.uuid(value, "generationId").equals(config.generationId())) {
            throw new IllegalArgumentException("Wrong network server, generation, or schema");
        }
    }

    private static UUID optionalUuid(JsonObject value, String key) {
        var entry = value.get(key);
        return entry == null || entry.isJsonNull() ? null : NetworkFiles.uuid(value, key);
    }

    private JsonObject identity() {
        var value = new JsonObject();
        value.addProperty("schemaVersion", 1);
        value.addProperty("serverId", config.serverId());
        value.addProperty("generationId", config.generationId().toString());
        value.addProperty("state", state);
        value.add("roundId", NetworkFiles.JSON.toJsonTree(roundId));
        value.add("endedRoundId", NetworkFiles.JSON.toJsonTree(endedRoundId));
        value.add("commandId", NetworkFiles.JSON.toJsonTree(commandId));
        value.add("resetRequestId", NetworkFiles.JSON.toJsonTree(resetRequestId));
        value.add("participants", NetworkFiles.JSON.toJsonTree(participants));
        value.addProperty("swapIntervalSeconds", swapIntervalSeconds);
        return value;
    }

    private void persistUsed() throws IOException { NetworkFiles.write(config.directory().resolve("used.json"), identity()); }

    private void writeStatus() throws IOException {
        var value = identity();
        value.addProperty("bootId", bootId.toString());
        value.addProperty("updatedAt", System.currentTimeMillis());
        var players = online.get();
        value.addProperty("playerCount", players.size());
        value.add("onlinePlayers", NetworkFiles.JSON.toJsonTree(players.stream().map(ServerPlayer::getUUID).toList()));
        NetworkFiles.write(config.directory().resolve("status.json"), value);
    }

    void close() {
        state = "stopped";
        game.setPaused(true);
        try { writeStatus(); }
        catch (IOException e) { DeathSwapMod.LOGGER.error("Cannot publish stopped Death Swap network status", e); }
    }

    private void fail(Exception error) {
        failed = true;
        state = "stopped";
        game.setPaused(true);
        game.setManagedStatus("Network round paused because a state file could not be saved. Check the server log.");
        DeathSwapMod.LOGGER.error("Death Swap network failed; preserving this world and stopping round progress", error);
        try { writeStatus(); }
        catch (IOException ignored) { /* The proxy will reject the stale heartbeat. */ }
    }

    private void announce(String text) {
        for (var player : online.get()) player.sendSystemMessage(Component.literal("[Death Swap] " + text));
    }
}
