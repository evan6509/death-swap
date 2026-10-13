package com.deathswap.proxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class RoundControllerTest {
    @TempDir Path temporary;
    private final long now = 100_000;
    private final FakeConnections connections = new FakeConnections();
    private NetworkConfig config;
    private RoundController controller;
    private Path stateFile;
    private String generationA;
    private String generationB;
    private String initialRound;
    private String initialCommand;
    private UUID one;
    private UUID two;

    @BeforeEach
    void setup() throws Exception {
        generationA = UUID.randomUUID().toString();
        generationB = UUID.randomUUID().toString();
        config = new NetworkConfig();
        config.backendServers = new LinkedHashMap<>();
        config.backendServers.put("a", new NetworkConfig.Backend("a", temporary.resolve("a/status.json")));
        config.backendServers.put("b", new NetworkConfig.Backend("b", temporary.resolve("b/status.json")));
        stateFile = temporary.resolve("proxy/state.json");
        writeStatus("a", "ready", null, null, List.of());
        writeStatus("b", "ready", null, null, List.of());
        controller = new RoundController(config, stateFile, connections, () -> now);
        controller.tick();
        RoundController.Control initial = control("a");
        initialRound = initial.roundId();
        initialCommand = initial.commandId();
        writeStatus("a", "waiting", initialRound, initialCommand, List.of());
        controller.tick();
        one = UUID.randomUUID();
        two = UUID.randomUUID();
        connections.locations.put(one, "a");
        connections.locations.put(two, "a");
    }

    @Test
    void requiresExactPrepareAcknowledgmentAndMovesObservers() throws Exception {
        UUID observer = UUID.randomUUID();
        connections.locations.put(observer, "a");
        endRound();
        controller.tick();
        assertEquals(Set.of(one.toString(), two.toString(), observer.toString()), new HashSet<>(control("b").participants()));
        assertTrue(connections.attempts.isEmpty());
        assertTrue(controller.route().isEmpty());

        RoundController.Control command = control("b");
        writeStatus("b", "prepared", command.roundId(), UUID.randomUUID().toString(), command.participants());
        controller.tick();
        assertTrue(connections.attempts.isEmpty());

        acknowledgeDestination();
        controller.tick();
        assertEquals(Set.of(one, two, observer), connections.attempts.keySet());
        assertEquals("b", controller.route().orElseThrow());
        assertFalse(Files.exists(config.resetFile("a")));
    }

    @Test
    void failedPartialTransferRetriesAndNeverResetsOccupiedSource() throws Exception {
        prepareAndConnect();
        connections.finish(one, true);
        connections.finish(two, false);
        writeStatus("a", "ended", initialRound, initialCommand, List.of(one.toString(), two.toString()));
        controller.tick();
        assertEquals(2, connections.attemptCount.get(two));
        assertFalse(Files.exists(config.resetFile("a")));
        assertEquals("a", connections.locations.get(two));

        connections.finish(two, true);
        // The older heartbeat still reports a player even though Velocity already moved them.
        controller.tick();
        assertFalse(Files.exists(config.resetFile("a")));
        writeStatus("a", "ended", initialRound, initialCommand, List.of(one.toString(), two.toString()));
        controller.tick();
        assertTrue(Files.exists(config.resetFile("a")));
        RoundController.Reset reset = JsonFiles.read(config.resetFile("a"), RoundController.Reset.class);
        assertEquals(generationA, reset.generationId());
        assertEquals("a", reset.serverId());
        assertEquals("resetting", persisted().transfer.phase);
    }

    @Test
    void transactionSurvivesRestartWithoutDuplicateReservationOrEarlyReset() throws Exception {
        prepareAndConnect();
        RoundController.Control reserved = control("b");
        connections.finish(one, true);
        connections.finish(two, false);
        controller = new RoundController(config, stateFile, connections, () -> now);
        controller.tick();
        assertEquals(reserved.roundId(), control("b").roundId());
        assertEquals(generationB, persisted().transfer.destinationGeneration);
        assertFalse(Files.exists(config.resetFile("a")));
        connections.finish(two, true);
        writeStatus("a", "ended", initialRound, initialCommand, List.of(one.toString(), two.toString()));
        controller.tick();
        RoundController.Reset reset = JsonFiles.read(config.resetFile("a"), RoundController.Reset.class);
        controller = new RoundController(config, stateFile, connections, () -> now);
        controller.tick();
        assertEquals(reset.requestId(), JsonFiles.read(config.resetFile("a"), RoundController.Reset.class).requestId());
        BackendStatus source = readStatus("a");
        source.state = "resetting";
        source.resetRequestId = reset.requestId();
        JsonFiles.write(config.statusFile("a"), source);
        controller.tick();
        assertNull(persisted().transfer);
        assertEquals("b", persisted().activeServer);
        assertTrue(persisted().usedGenerations.contains(generationA));
        String consumed = generationA;
        generationA = UUID.randomUUID().toString();
        writeStatus("a", "ready", null, null, List.of());
        controller.tick();
        assertNull(persisted().transfer);
        assertFalse(persisted().usedGenerations.contains(consumed));
        assertTrue(persisted().retiredGenerations.isEmpty());
    }

    @Test
    void disconnectedPlayersArePrunedBeforeDestinationStarts() throws Exception {
        endRound();
        controller.tick();
        acknowledgeDestination();
        String originalCommand = control("b").commandId();
        connections.locations.remove(two);
        writeStatus("a", "ended", initialRound, initialCommand, List.of(one.toString(), two.toString()));
        controller.tick();
        assertEquals(List.of(one.toString()), control("b").participants());
        assertNotEquals(originalCommand, control("b").commandId());
        assertTrue(connections.attempts.isEmpty());
        acknowledgeDestination();
        controller.tick();
        assertEquals(Set.of(one), connections.attempts.keySet());
    }

    @Test
    void staleFutureUsedOrWrongGenerationBackendCannotBecomeDestination() throws Exception {
        endRound();
        BackendStatus destination = readStatus("b");
        destination.updatedAt = now - 5_001;
        JsonFiles.write(config.statusFile("b"), destination);
        controller.tick();
        assertNull(persisted().transfer.destination);
        destination.updatedAt = now + 1;
        JsonFiles.write(config.statusFile("b"), destination);
        controller.tick();
        assertNull(persisted().transfer.destination);
        destination.updatedAt = now;
        destination.serverId = "wrong";
        JsonFiles.write(config.statusFile("b"), destination);
        controller.tick();
        assertNull(persisted().transfer.destination);
        destination.serverId = "b";
        JsonFiles.write(config.statusFile("b"), destination);
        RoundController.State persisted = persisted();
        persisted.usedGenerations.add(generationB);
        JsonFiles.write(stateFile, persisted);
        controller = new RoundController(config, stateFile, connections, () -> now);
        controller.tick();
        assertNull(persisted().transfer.destination);
        assertFalse(Files.exists(config.controlFile("b")));
    }

    @Test
    void occupiedDestinationIsNeverReservedEvenWhenHeartbeatClaimsEmpty() throws Exception {
        connections.locations.put(UUID.randomUUID(), "b");
        endRound();
        controller.tick();
        assertNull(persisted().transfer.destination);
        assertFalse(Files.exists(config.controlFile("b")));
    }

    @Test
    void reconnectRoutingRejectsStaleGenerationAndResettingBackends() throws Exception {
        assertEquals("a", controller.route().orElseThrow());
        BackendStatus active = readStatus("a");
        active.updatedAt = now - 6_000;
        JsonFiles.write(config.statusFile("a"), active);
        controller.tick();
        assertTrue(controller.route().isEmpty());
        active.updatedAt = now;
        active.generationId = UUID.randomUUID().toString();
        JsonFiles.write(config.statusFile("a"), active);
        controller.tick();
        assertTrue(controller.route().isEmpty());
        active.generationId = generationA;
        active.state = "resetting";
        JsonFiles.write(config.statusFile("a"), active);
        controller.tick();
        assertTrue(controller.route().isEmpty());
    }

    @Test
    void corruptedDurableStateFailsClosed() throws Exception {
        Files.writeString(stateFile, "{broken");
        assertThrows(java.io.IOException.class,
            () -> new RoundController(config, stateFile, connections, () -> now));
    }

    @Test
    void missingStateNeverSplitsAnOccupiedDeploymentIntoAnotherWorld() throws Exception {
        Files.delete(stateFile);
        // Even a heartbeat written just before a join cannot hide actual proxy connections.
        BackendStatus source = readStatus("a");
        source.state = "ready";
        source.onlinePlayers = List.of();
        source.playerCount = 0;
        JsonFiles.write(config.statusFile("a"), source);
        controller = new RoundController(config, stateFile, connections, () -> now);
        controller.tick();
        assertFalse(Files.exists(stateFile));
        assertFalse(Files.exists(config.controlFile("b")));
        assertTrue(controller.route().isEmpty());
    }

    @Test
    void generationChangeAfterAcknowledgedResetCompletesRecoveredTransaction() throws Exception {
        prepareAndConnect();
        connections.finish(one, true);
        connections.finish(two, true);
        writeStatus("a", "ended", initialRound, initialCommand, List.of(one.toString(), two.toString()));
        controller.tick();
        String consumed = generationA;
        generationA = UUID.randomUUID().toString();
        writeStatus("a", "ready", null, null, List.of());
        controller = new RoundController(config, stateFile, connections, () -> now);
        controller.tick();
        assertNull(persisted().transfer);
        assertEquals("b", persisted().activeServer);
        assertFalse(persisted().usedGenerations.contains(consumed));
    }

    private void prepareAndConnect() throws Exception {
        endRound();
        controller.tick();
        acknowledgeDestination();
        controller.tick();
    }

    private void endRound() throws Exception {
        writeStatus("a", "ended", initialRound, initialCommand, List.of(one.toString(), two.toString()));
    }

    private void acknowledgeDestination() throws Exception {
        RoundController.Control command = control("b");
        writeStatus("b", "prepared", command.roundId(), command.commandId(), command.participants());
    }

    private RoundController.Control control(String backend) throws Exception {
        return JsonFiles.read(config.controlFile(backend), RoundController.Control.class);
    }

    private RoundController.State persisted() throws Exception { return JsonFiles.read(stateFile, RoundController.State.class); }
    private BackendStatus readStatus(String backend) throws Exception { return JsonFiles.read(config.statusFile(backend), BackendStatus.class); }

    private void writeStatus(String backend, String state, String round, String command, List<String> participants) throws Exception {
        BackendStatus status = new BackendStatus();
        status.schemaVersion = 1;
        status.serverId = backend;
        status.generationId = "a".equals(backend) ? generationA : generationB;
        status.bootId = UUID.randomUUID().toString();
        status.updatedAt = now;
        status.state = state;
        status.onlinePlayers = connections.playersOn(backend).stream().map(UUID::toString).toList();
        status.playerCount = status.onlinePlayers.size();
        status.roundId = round;
        status.commandId = command;
        status.participants = participants;
        status.endedRoundId = "ended".equals(state) ? round : null;
        JsonFiles.write(config.statusFile(backend), status);
    }

    private static final class FakeConnections implements RoundController.Connections {
        final Map<UUID, String> locations = new HashMap<>();
        final Map<UUID, CompletableFuture<Boolean>> attempts = new HashMap<>();
        final Map<UUID, Integer> attemptCount = new HashMap<>();
        final Map<UUID, String> targets = new HashMap<>();

        @Override public boolean registered(String backend) { return Set.of("a", "b").contains(backend); }
        @Override public Set<UUID> playersOn(String backend) {
            Set<UUID> players = new HashSet<>();
            locations.forEach((uuid, server) -> { if (backend.equals(server)) players.add(uuid); });
            return players;
        }
        @Override public Set<UUID> onlinePlayers() { return Set.copyOf(locations.keySet()); }
        @Override public String currentServer(UUID player) { return locations.get(player); }
        @Override public CompletableFuture<Boolean> connect(UUID player, String backend) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            attempts.put(player, result);
            targets.put(player, backend);
            attemptCount.merge(player, 1, Integer::sum);
            return result;
        }
        void finish(UUID player, boolean successful) {
            if (successful) locations.put(player, targets.get(player));
            attempts.get(player).complete(successful);
        }
    }
}
