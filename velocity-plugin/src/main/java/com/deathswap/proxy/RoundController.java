package com.deathswap.proxy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** The durable coordinator; Minecraft and the supervisor remain the only world owners. */
final class RoundController {
    interface Connections {
        boolean registered(String backend);
        Set<UUID> playersOn(String backend);
        Set<UUID> onlinePlayers();
        String currentServer(UUID player);
        CompletableFuture<Boolean> connect(UUID player, String backend);
    }

    private final NetworkConfig config;
    private final Path stateFile;
    private final Connections connections;
    private final LongSupplier clock;
    private final Map<String, BackendStatus> statuses = new HashMap<>();
    private final Set<UUID> connecting = new HashSet<>();
    private State state;

    RoundController(NetworkConfig config, Path stateFile, Connections connections, LongSupplier clock) throws IOException {
        config.validate();
        this.config = config;
        this.stateFile = stateFile;
        this.connections = connections;
        this.clock = clock;
        state = Files.exists(stateFile) ? JsonFiles.read(stateFile, State.class) : new State();
        validateState();
    }

    /** Single synchronized poll: API event threads and async connections share this lock. */
    synchronized void tick() throws IOException {
        refreshStatuses();
        retireRegeneratedWorlds();
        if (state.activeServer == null) {
            initialize();
            return;
        }
        if (state.transfer != null) {
            advanceTransfer();
            return;
        }
        BackendStatus active = matching(state.activeServer, state.activeGeneration);
        if (active == null) return;
        if ("ready".equals(active.state)) {
            writeControl(state.activeServer, state.activeGeneration, state.activeCommandId,
                "activate", state.activeRoundId, List.of());
            return;
        }
        if ("ended".equals(active.state) && BackendStatus.uuid(active.endedRoundId)
            && active.endedRoundId.equals(active.roundId)
            && !active.endedRoundId.equals(state.lastEndedRoundId)) {
            State next = state.copy();
            Transfer transfer = new Transfer();
            transfer.source = state.activeServer;
            transfer.sourceGeneration = state.activeGeneration;
            transfer.sourceEndedRoundId = active.endedRoundId;
            transfer.roundId = id();
            transfer.phase = "waiting";
            transfer.participants = names(connections.playersOn(transfer.source));
            next.transfer = transfer;
            commit(next);
            advanceTransfer();
        }
    }

    /** All initial joins, manual changes, and reconnects are restricted to the one current round. */
    synchronized Optional<String> route() {
        if (state.transfer != null) {
            Transfer transfer = state.transfer;
            if (transfer.destination == null) return Optional.empty();
            BackendStatus destination = matching(transfer.destination, transfer.destinationGeneration);
            if (destination != null && transfer.roundId.equals(destination.roundId)
                && transfer.commandId.equals(destination.commandId)
                && Set.of("prepared", "running", "waiting", "ended").contains(destination.state)) {
                return Optional.of(transfer.destination);
            }
            return Optional.empty();
        }
        BackendStatus active = matching(state.activeServer, state.activeGeneration);
        if (active != null && state.activeCommandId.equals(active.commandId)
            && Set.of("waiting", "running", "ended", "prepared").contains(active.state)) {
            return Optional.of(state.activeServer);
        }
        return Optional.empty();
    }

    synchronized String describe() {
        String active = state.activeServer == null ? "awaiting a fresh backend" : state.activeServer;
        if (state.transfer == null) {
            BackendStatus status = statuses.get(state.activeServer);
            return "Death Swap: " + active + "; " + (status == null ? "unavailable" : status.state);
        }
        Transfer t = state.transfer;
        return "Death Swap: " + t.source + " -> " + (t.destination == null ? "awaiting a fresh backend" : t.destination)
            + "; " + t.phase + "; roster=" + t.participants.size();
    }

    private void initialize() throws IOException {
        // Losing proxy state must not split an occupied/used deployment into a second round.
        if (statuses.values().stream().anyMatch(s -> !"ready".equals(s.state) && !"stopped".equals(s.state))
            || config.backendServers.keySet().stream().anyMatch(name -> !connections.playersOn(name).isEmpty())) return;
        String destination = freshDestination(null);
        if (destination == null) return;
        BackendStatus status = statuses.get(destination);
        State next = state.copy();
        next.activeServer = destination;
        next.activeGeneration = status.generationId;
        next.activeRoundId = id();
        next.activeCommandId = id();
        next.usedGenerations.add(status.generationId);
        commit(next);
        writeControl(destination, status.generationId, state.activeCommandId, "activate", state.activeRoundId, List.of());
    }

    private void advanceTransfer() throws IOException {
        Transfer t = state.transfer;
        if ("resetting".equals(t.phase)) {
            advanceReset(t);
            return;
        }
        BackendStatus source = matching(t.source, t.sourceGeneration);
        if (source == null || !"ended".equals(source.state) || !t.sourceEndedRoundId.equals(source.endedRoundId)) return;
        if (t.destination == null) {
            String destination = freshDestination(t.source);
            if (destination == null) return;
            State next = state.copy();
            next.transfer.destination = destination;
            next.transfer.destinationGeneration = statuses.get(destination).generationId;
            next.transfer.commandId = id();
            next.transfer.phase = "preparing";
            next.usedGenerations.add(next.transfer.destinationGeneration);
            commit(next);
            t = state.transfer;
        }
        BackendStatus destination = matching(t.destination, t.destinationGeneration);
        if (destination == null) return;

        if ("ready".equals(destination.state) || "prepared".equals(destination.state)) {
            List<String> roster = onlineRoster(t);
            if (!roster.equals(t.participants)) {
                State next = state.copy();
                next.transfer.participants = roster;
                next.transfer.commandId = id();
                next.transfer.phase = "preparing";
                commit(next);
                t = state.transfer;
            }
        }
        if ("preparing".equals(t.phase)) {
            writeControl(t.destination, t.destinationGeneration, t.commandId, "prepare", t.roundId, t.participants);
            // A cached prepared heartbeat can race start. The backend owns a finalized round's roster.
            boolean finalized = t.roundId.equals(destination.roundId) && BackendStatus.uuid(destination.commandId)
                && Set.of("running", "waiting", "ended").contains(destination.state);
            if (!finalized && (!t.commandId.equals(destination.commandId) || !t.roundId.equals(destination.roundId)
                || !"prepared".equals(destination.state))) return;
            State next = state.copy();
            next.transfer.phase = "connecting";
            if (finalized) {
                next.transfer.commandId = destination.commandId;
                next.transfer.participants = new ArrayList<>(destination.participants);
            }
            commit(next);
            t = state.transfer;
        }
        if (!"connecting".equals(t.phase) || !t.roundId.equals(destination.roundId)
            || !Set.of("prepared", "running", "waiting", "ended").contains(destination.state)) return;
        connectMissing(t);

        // Heartbeat and Velocity must agree. A successful future alone is not enough to delete a world.
        Set<UUID> online = connections.onlinePlayers();
        String destinationName = t.destination;
        boolean rosterArrived = t.participants.stream().map(UUID::fromString).filter(online::contains)
            .allMatch(player -> destinationName.equals(connections.currentServer(player)));
        if (!rosterArrived || !connecting.isEmpty() || !connections.playersOn(t.source).isEmpty()
            || source.playerCount != 0 || !source.onlinePlayers.isEmpty()) return;

        State next = state.copy();
        next.transfer.phase = "resetting";
        next.transfer.resetRequestId = id();
        next.transfer.resetRequestedAt = clock.getAsLong();
        commit(next);
        writeReset(state.transfer);
    }

    private void advanceReset(Transfer t) throws IOException {
        if (!connections.playersOn(t.source).isEmpty()) return;
        BackendStatus source = statuses.get(t.source);
        if (source == null) return;
        boolean acknowledged = t.sourceGeneration.equals(source.generationId)
            && "resetting".equals(source.state) && source.playerCount == 0
            && t.resetRequestId.equals(source.resetRequestId);
        boolean regenerated = !t.sourceGeneration.equals(source.generationId);
        if (!acknowledged && !regenerated) {
            if (t.sourceGeneration.equals(source.generationId) && "ended".equals(source.state)
                && t.sourceEndedRoundId.equals(source.endedRoundId) && source.playerCount == 0) writeReset(t);
            return;
        }
        State next = state.copy();
        next.activeServer = t.destination;
        next.activeGeneration = t.destinationGeneration;
        next.activeRoundId = t.roundId;
        next.activeCommandId = t.commandId;
        next.lastEndedRoundId = t.sourceEndedRoundId;
        if (regenerated) next.usedGenerations.remove(t.sourceGeneration);
        else next.retiredGenerations.put(t.source, t.sourceGeneration);
        next.transfer = null;
        commit(next);
    }

    /** Keep a source's consumed identity until a new supervised generation is positively observed. */
    private void retireRegeneratedWorlds() throws IOException {
        State next = null;
        for (var entry : state.retiredGenerations.entrySet()) {
            BackendStatus current = statuses.get(entry.getKey());
            String retired = entry.getValue();
            if (current == null || retired.equals(current.generationId) || retired.equals(state.activeGeneration)
                || state.transfer != null && (retired.equals(state.transfer.sourceGeneration)
                    || retired.equals(state.transfer.destinationGeneration))) continue;
            if (next == null) next = state.copy();
            next.usedGenerations.remove(retired);
            next.retiredGenerations.remove(entry.getKey());
        }
        if (next != null) commit(next);
    }

    private void connectMissing(Transfer t) {
        Set<UUID> players = new LinkedHashSet<>(connections.playersOn(t.source));
        Set<UUID> online = connections.onlinePlayers();
        for (String value : t.participants) {
            UUID player = UUID.fromString(value);
            if (online.contains(player)) players.add(player);
        }
        for (UUID player : players) {
            if (t.destination.equals(connections.currentServer(player)) || connecting.contains(player)
                || connections.currentServer(player) == null) continue;
            connecting.add(player);
            try {
                connections.connect(player, t.destination).orTimeout(10, TimeUnit.SECONDS)
                    .whenComplete((ignored, error) -> {
                        synchronized (RoundController.this) { connecting.remove(player); }
                    });
            } catch (RuntimeException e) {
                connecting.remove(player);
            }
        }
    }

    private List<String> onlineRoster(Transfer t) {
        Set<UUID> online = connections.onlinePlayers();
        LinkedHashSet<String> roster = new LinkedHashSet<>();
        t.participants.stream().filter(value -> online.contains(UUID.fromString(value))).forEach(roster::add);
        connections.playersOn(t.source).stream().map(UUID::toString).sorted().forEach(roster::add);
        return new ArrayList<>(roster);
    }

    private String freshDestination(String except) {
        for (String name : config.backendServers.keySet()) {
            BackendStatus status = statuses.get(name);
            if (!name.equals(except) && connections.registered(name) && status != null && "ready".equals(status.state)
                && !state.usedGenerations.contains(status.generationId) && status.playerCount == 0
                && connections.playersOn(name).isEmpty()) return name;
        }
        return null;
    }

    private BackendStatus matching(String name, String generation) {
        BackendStatus status = statuses.get(name);
        return status != null && status.generationId.equals(generation) && fresh(status) ? status : null;
    }

    private boolean fresh(BackendStatus status) {
        return status.valid(status.serverId, clock.getAsLong(), config.statusStaleSeconds);
    }

    private void refreshStatuses() {
        statuses.clear();
        long now = clock.getAsLong();
        for (var entry : config.backendServers.entrySet()) {
            try {
                BackendStatus status = JsonFiles.read(config.statusFile(entry.getKey()), BackendStatus.class);
                if (status.valid(entry.getValue().serverId, now, config.statusStaleSeconds)) statuses.put(entry.getKey(), status);
            } catch (IOException ignored) { /* Missing/stale/malformed data is unavailable, never ready. */ }
        }
    }

    private void writeControl(String server, String generation, String command, String action,
                              String round, List<String> participants) throws IOException {
        JsonFiles.write(config.controlFile(server), new Control(1, config.backendServers.get(server).serverId,
            generation, command, action, round, List.copyOf(participants), config.swapIntervalSeconds));
    }

    private void writeReset(Transfer t) throws IOException {
        if (t.resetRequestedAt == null) {
            // Upgrade an in-flight transaction without invalidating an existing supervisor receipt.
            long requestedAt = clock.getAsLong();
            if (Files.exists(config.resetFile(t.source))) {
                Reset pending = JsonFiles.read(config.resetFile(t.source), Reset.class);
                if (pending.schemaVersion() != 1 || !config.backendServers.get(t.source).serverId.equals(pending.serverId())
                    || !t.sourceGeneration.equals(pending.generationId()) || !t.resetRequestId.equals(pending.requestId())
                    || pending.requestedAt() < 0) throw new IOException("Pending reset does not match transfer transaction");
                requestedAt = pending.requestedAt();
            }
            State next = state.copy();
            next.transfer.resetRequestedAt = requestedAt;
            commit(next);
            t = state.transfer;
        }
        JsonFiles.write(config.resetFile(t.source), new Reset(1, config.backendServers.get(t.source).serverId,
            t.sourceGeneration, t.resetRequestId, t.resetRequestedAt));
    }

    private void commit(State next) throws IOException {
        JsonFiles.write(stateFile, next);
        state = next;
    }

    private void validateState() throws IOException {
        if (state.schemaVersion != 1 || state.usedGenerations == null || state.retiredGenerations == null
            || !state.usedGenerations.stream().allMatch(BackendStatus::uuid)
            || !BackendStatus.nullableUuid(state.lastEndedRoundId)) throw new IOException("Invalid proxy state");
        for (var entry : state.retiredGenerations.entrySet()) {
            if (!config.backendServers.containsKey(entry.getKey()) || !BackendStatus.uuid(entry.getValue())
                || !state.usedGenerations.contains(entry.getValue())) throw new IOException("Invalid retired generation identity");
        }
        if (state.activeServer == null) {
            if (state.transfer != null || state.activeGeneration != null) throw new IOException("Invalid active backend state");
            return;
        }
        if (!config.backendServers.containsKey(state.activeServer) || !BackendStatus.uuid(state.activeGeneration)
            || !BackendStatus.uuid(state.activeCommandId) || !BackendStatus.uuid(state.activeRoundId)
            || !state.usedGenerations.contains(state.activeGeneration)) throw new IOException("Invalid active backend identity");
        Transfer t = state.transfer;
        if (t == null) return;
        if (!state.activeServer.equals(t.source) || !state.activeGeneration.equals(t.sourceGeneration)
            || !BackendStatus.uuid(t.sourceEndedRoundId) || !BackendStatus.uuid(t.roundId)
            || t.participants == null || !t.participants.stream().allMatch(BackendStatus::uuid)
            || new HashSet<>(t.participants).size() != t.participants.size()
            || !Set.of("waiting", "preparing", "connecting", "resetting").contains(t.phase)) {
            throw new IOException("Invalid transfer transaction");
        }
        if (t.destination != null && (!config.backendServers.containsKey(t.destination) || t.source.equals(t.destination)
            || !BackendStatus.uuid(t.destinationGeneration) || !BackendStatus.uuid(t.commandId)
            || !state.usedGenerations.contains(t.destinationGeneration))) throw new IOException("Invalid reserved destination");
        if ((t.destination == null && !"waiting".equals(t.phase))
            || ("resetting".equals(t.phase) && !BackendStatus.uuid(t.resetRequestId))
            || t.resetRequestedAt != null && t.resetRequestedAt < 0) {
            throw new IOException("Invalid transaction phase");
        }
    }

    private static String id() { return UUID.randomUUID().toString(); }
    private static List<String> names(Set<UUID> players) { return players.stream().map(UUID::toString).sorted().toList(); }

    record Control(int schemaVersion, String serverId, String generationId, String commandId,
                   String action, String roundId, List<String> participants, int swapIntervalSeconds) { }
    record Reset(int schemaVersion, String serverId, String generationId, String requestId, long requestedAt) { }

    static final class State {
        int schemaVersion = 1;
        String activeServer;
        String activeGeneration;
        String activeRoundId;
        String activeCommandId;
        String lastEndedRoundId;
        Set<String> usedGenerations = new LinkedHashSet<>();
        Map<String, String> retiredGenerations = new LinkedHashMap<>();
        Transfer transfer;

        State copy() {
            State copy = new State();
            copy.activeServer = activeServer;
            copy.activeGeneration = activeGeneration;
            copy.activeRoundId = activeRoundId;
            copy.activeCommandId = activeCommandId;
            copy.lastEndedRoundId = lastEndedRoundId;
            copy.usedGenerations = new LinkedHashSet<>(usedGenerations);
            copy.retiredGenerations = new LinkedHashMap<>(retiredGenerations);
            copy.transfer = transfer == null ? null : transfer.copy();
            return copy;
        }
    }

    static final class Transfer {
        String source;
        String sourceGeneration;
        String sourceEndedRoundId;
        String destination;
        String destinationGeneration;
        String commandId;
        String roundId;
        List<String> participants = new ArrayList<>();
        String phase;
        String resetRequestId;
        Long resetRequestedAt;

        Transfer copy() {
            Transfer copy = new Transfer();
            copy.source = source;
            copy.sourceGeneration = sourceGeneration;
            copy.sourceEndedRoundId = sourceEndedRoundId;
            copy.destination = destination;
            copy.destinationGeneration = destinationGeneration;
            copy.commandId = commandId;
            copy.roundId = roundId;
            copy.participants = new ArrayList<>(participants);
            copy.phase = phase;
            copy.resetRequestId = resetRequestId;
            copy.resetRequestedAt = resetRequestedAt;
            return copy;
        }
    }
}
