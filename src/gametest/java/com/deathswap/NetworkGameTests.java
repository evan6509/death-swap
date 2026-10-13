package com.deathswap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Exercises the filesystem seam and real server player lifecycle without a proxy. */
public final class NetworkGameTests {
    @GameTest
    public void activationWaitsForTwoPlayersAndAcknowledgesCommand(GameTestHelper helper) throws IOException {
        var a = player(helper, 1);
        var b = player(helper, 3);
        var fixture = fixture(helper, List.of(a));
        UUID command = fixture.control("activate", UUID.randomUUID(), List.of(), fixture.generation);
        fixture.advance(3);
        helper.assertTrue(fixture.coordinator.state().equals("waiting") && !fixture.game.running(),
                "An activated initial lobby cannot start with just one player");
        helper.assertTrue(command.toString().equals(fixture.status().get("commandId").getAsString()),
                "The accepted activation is acknowledged");
        fixture.online.add(b.getUUID());
        fixture.coordinator.joinedOrRespawned(b);
        fixture.advance(3);
        helper.assertTrue(fixture.coordinator.state().equals("running") && fixture.game.running(),
                "A second arrival starts the initial round");
        helper.assertTrue(a.gameMode() == GameType.SURVIVAL && b.gameMode() == GameType.SURVIVAL,
                "The initial roster enters survival together");
        helper.succeed();
    }

    @GameTest
    public void wrongGenerationCannotClaimFreshWorld(GameTestHelper helper) throws IOException {
        var fixture = fixture(helper, List.of());
        fixture.control("prepare", UUID.randomUUID(), List.of(UUID.randomUUID(), UUID.randomUUID()), UUID.randomUUID());
        fixture.advance(3);
        helper.assertTrue(fixture.coordinator.state().equals("ready") && !fixture.game.running(),
                "A stale generation command cannot reserve the current fresh world");
        helper.assertTrue(fixture.status().get("commandId").isJsonNull(), "Wrong-generation command has no acknowledgment");
        helper.succeed();
    }

    @GameTest
    public void firstDeathFreezesRoundAndDuplicateDeathsKeepOneIdentity(GameTestHelper helper) throws IOException {
        var a = player(helper, 1);
        var b = player(helper, 3);
        var c = player(helper, 4);
        var fixture = fixture(helper, List.of(a, b, c));
        fixture.activate();
        UUID round = UUID.fromString(fixture.status().get("roundId").getAsString());
        Vec3 survivorPosition = b.position();
        a.setHealth(0);
        fixture.coordinator.onDeath(a);
        fixture.advance(1);
        String frozenMatch = fixture.game.status();
        fixture.coordinator.onDeath(c);
        fixture.coordinator.onDeath(a);
        fixture.advance(250);
        var status = fixture.status();
        helper.assertTrue(fixture.coordinator.state().equals("ended"), "Participant death ends the network round");
        helper.assertTrue(round.toString().equals(status.get("endedRoundId").getAsString()),
                "Simultaneous and duplicate deaths retain the same ended-round event");
        helper.assertTrue(fixture.game.status().equals(frozenMatch) && b.position().equals(survivorPosition),
                "An ended source keeps its swap clock and survivor position frozen");
        var respawned = fixture.lookup(a.getUUID());
        helper.assertTrue(respawned != null && respawned.isAlive() && respawned.gameMode() == GameType.SPECTATOR,
                "A dead participant is automatically respawned into spectator on a later tick");
        helper.assertTrue(b.gameMode() == GameType.SPECTATOR && c.gameMode() == GameType.SPECTATOR,
                "The entire source roster is held in spectator while waiting for transfer");
        helper.succeed();
    }

    @GameTest
    public void observerDeathDoesNotEndActiveRound(GameTestHelper helper) throws IOException {
        var a = player(helper, 1);
        var b = player(helper, 3);
        var observer = player(helper, 4);
        var fixture = fixture(helper, List.of(a, b));
        fixture.activate();
        fixture.online.add(observer.getUUID());
        fixture.coordinator.joinedOrRespawned(observer);
        helper.assertTrue(observer.gameMode() == GameType.SPECTATOR, "A late arrival observes the running round");
        fixture.coordinator.onDeath(observer);
        fixture.advance(3);
        helper.assertTrue(fixture.coordinator.state().equals("running") && fixture.game.running(),
                "Deaths of players outside the active roster cannot rotate the world");
        helper.assertTrue(fixture.status().get("endedRoundId").isJsonNull(), "Observer death creates no round-end event");
        helper.succeed();
    }

    @GameTest
    public void participantDisconnectPausesAndReconnectResumesSameRound(GameTestHelper helper) throws IOException {
        var a = player(helper, 1);
        var b = player(helper, 3);
        var fixture = fixture(helper, List.of(a, b));
        fixture.activate();
        String round = fixture.status().get("roundId").getAsString();
        Vec3 fromA = a.position(), fromB = b.position();
        fixture.online.remove(a.getUUID());
        fixture.coordinator.disconnected(a);
        fixture.advance(1);
        String frozenMatch = fixture.game.status();
        fixture.advance(250);
        helper.assertTrue(fixture.coordinator.state().equals("running") && fixture.game.status().equals(frozenMatch)
                        && b.position().equals(fromB) && fixture.status().get("endedRoundId").isJsonNull(),
                "A disconnect pauses the active round without eliminating players or requesting a fresh seed");
        fixture.online.add(a.getUUID());
        fixture.coordinator.joinedOrRespawned(a);
        fixture.advance(200);
        helper.assertTrue(round.equals(fixture.status().get("roundId").getAsString())
                        && a.position().equals(fromB) && b.position().equals(fromA),
                "A participant reconnect resumes swapping in the same round and generation");
        helper.succeed();
    }

    @GameTest
    public void preparedDestinationStartsOnlyWhenFullRosterArrives(GameTestHelper helper) throws IOException {
        var a = player(helper, 1);
        var b = player(helper, 3);
        var fixture = fixture(helper, List.of());
        UUID round = UUID.randomUUID();
        UUID command = fixture.control("prepare", round, List.of(a.getUUID(), b.getUUID()), fixture.generation);
        fixture.advance(2);
        helper.assertTrue(fixture.coordinator.state().equals("prepared") && !fixture.game.running(),
                "An empty destination is reserved before transfer begins");
        helper.assertTrue(command.toString().equals(fixture.status().get("commandId").getAsString()),
                "Reservation acknowledgment precedes player arrival");
        fixture.online.add(a.getUUID());
        fixture.coordinator.joinedOrRespawned(a);
        fixture.advance(250);
        helper.assertTrue(!fixture.game.running() && a.gameMode() == GameType.SPECTATOR,
                "A partial transfer cannot start the clock or allow a head start");
        fixture.online.add(b.getUUID());
        fixture.coordinator.joinedOrRespawned(b);
        fixture.advance(2);
        helper.assertTrue(fixture.coordinator.state().equals("running") && fixture.game.running(),
                "The final expected arrival starts the destination round");
        helper.assertTrue(round.toString().equals(fixture.status().get("roundId").getAsString()),
                "The destination starts the reserved round identity");
        helper.assertTrue(a.gameMode() == GameType.SURVIVAL && b.gameMode() == GameType.SURVIVAL,
                "All reserved participants enter survival together");
        helper.succeed();
    }

    @GameTest
    public void preparedRosterCanDropDisconnectedTransferParticipant(GameTestHelper helper) throws IOException {
        var a = player(helper, 1);
        var b = player(helper, 3);
        var c = player(helper, 4);
        var fixture = fixture(helper, List.of());
        UUID round = UUID.randomUUID();
        fixture.control("prepare", round, List.of(a.getUUID(), b.getUUID(), c.getUUID()), fixture.generation);
        fixture.advance(2);
        fixture.online.add(a.getUUID());
        fixture.online.add(b.getUUID());
        fixture.coordinator.joinedOrRespawned(a);
        fixture.coordinator.joinedOrRespawned(b);
        fixture.advance(2);
        helper.assertTrue(!fixture.game.running(), "Missing transfer participant keeps the destination prepared");
        fixture.control("prepare", round, List.of(a.getUUID(), b.getUUID()), fixture.generation);
        fixture.advance(2);
        helper.assertTrue(fixture.game.running() && fixture.game.isActiveParticipant(a.getUUID())
                        && fixture.game.isActiveParticipant(b.getUUID()) && !fixture.game.isActiveParticipant(c.getUUID()),
                "A same-round roster update releases the connected players without adding the disconnected player");
        helper.succeed();
    }

    @GameTest
    public void resetAcknowledgmentRequiresVacatedEndedGeneration(GameTestHelper helper) throws IOException {
        var a = player(helper, 1);
        var b = player(helper, 3);
        var fixture = fixture(helper, List.of(a, b));
        fixture.activate();
        fixture.coordinator.onDeath(a);
        fixture.advance(2);
        UUID request = fixture.resetRequest(fixture.generation);
        fixture.advance(3);
        helper.assertTrue(fixture.coordinator.state().equals("ended") && !fixture.coordinator.rejectsJoins(),
                "A reset request cannot authorize stopping a source that still has players");
        helper.assertTrue(fixture.status().get("resetRequestId").isJsonNull(), "Occupied source does not acknowledge reset");
        fixture.online.clear();
        fixture.advance(3);
        helper.assertTrue(fixture.coordinator.state().equals("resetting") && fixture.coordinator.rejectsJoins(),
                "A vacated ended source enters terminal reset mode and rejects joins");
        helper.assertTrue(request.toString().equals(fixture.status().get("resetRequestId").getAsString()),
                "The heartbeat acknowledges the exact reset request");
        helper.succeed();
    }

    @GameTest
    public void wrongGenerationResetCannotAuthorizeDeletion(GameTestHelper helper) throws IOException {
        var a = player(helper, 1);
        var b = player(helper, 3);
        var fixture = fixture(helper, List.of(a, b));
        fixture.activate();
        fixture.coordinator.onDeath(a);
        fixture.advance(2);
        fixture.online.clear();
        fixture.resetRequest(UUID.randomUUID());
        fixture.advance(3);
        helper.assertTrue(fixture.coordinator.state().equals("ended") && fixture.status().get("resetRequestId").isJsonNull(),
                "A stale-generation reset request cannot authorize deleting the current world");
        helper.succeed();
    }

    @GameTest
    public void restartCannotAdvertiseUsedGenerationAsFresh(GameTestHelper helper) throws IOException {
        var a = player(helper, 1);
        var b = player(helper, 3);
        var fixture = fixture(helper, List.of(a, b));
        fixture.activate();
        UUID round = UUID.fromString(fixture.status().get("roundId").getAsString());
        fixture.coordinator.close();
        fixture.game.stop("Test process restart");
        fixture.online.clear();
        fixture.coordinator = new NetworkRoundCoordinator(helper.getLevel().getServer(), fixture.game,
                fixture.config, fixture::players);
        fixture.advance(3);
        helper.assertTrue(fixture.coordinator.state().equals("ended"),
                "Restarting an interrupted match preserves its used generation instead of exposing it as ready");
        helper.assertTrue(round.toString().equals(fixture.status().get("endedRoundId").getAsString()),
                "The interrupted active round becomes a stable round-end event after restart");
        helper.succeed();
    }

    @GameTest
    public void preparedRestartKeepsReservedSwapInterval(GameTestHelper helper) throws IOException {
        var a = player(helper, 1);
        var b = player(helper, 3);
        var fixture = fixture(helper, List.of());
        UUID round = UUID.randomUUID();
        fixture.control("prepare", round, List.of(a.getUUID(), b.getUUID()), fixture.generation, 20);
        fixture.advance(2);
        fixture.coordinator.close();
        fixture.coordinator = new NetworkRoundCoordinator(helper.getLevel().getServer(), fixture.game,
                fixture.config, fixture::players);
        fixture.online.add(a.getUUID());
        fixture.online.add(b.getUUID());
        fixture.coordinator.joinedOrRespawned(a);
        fixture.coordinator.joinedOrRespawned(b);
        fixture.advance(3);
        Vec3 fromA = a.position(), fromB = b.position();
        fixture.advance(200);
        helper.assertTrue(a.position().equals(fromA) && b.position().equals(fromB),
                "Restarted reservation uses its 20-second interval, not the 10-second config default");
        fixture.advance(200);
        helper.assertTrue(a.position().equals(fromB) && b.position().equals(fromA),
                "The persisted reservation interval still triggers its swap on time");
        helper.succeed();
    }

    @GameTest
    public void repeatingCommandIdCannotMutateReservedRoster(GameTestHelper helper) throws IOException {
        var a = player(helper, 1);
        var b = player(helper, 3);
        var c = player(helper, 4);
        var fixture = fixture(helper, List.of());
        UUID command = fixture.control("prepare", UUID.randomUUID(),
                List.of(a.getUUID(), b.getUUID(), c.getUUID()), fixture.generation);
        fixture.advance(2);
        fixture.online.add(a.getUUID());
        fixture.online.add(b.getUUID());
        fixture.coordinator.joinedOrRespawned(a);
        fixture.coordinator.joinedOrRespawned(b);
        var repeated = JsonParser.parseString(Files.readString(fixture.directory.resolve("control.json"))).getAsJsonObject();
        var changedRoster = new JsonArray();
        changedRoster.add(a.getUUID().toString());
        changedRoster.add(b.getUUID().toString());
        repeated.add("participants", changedRoster);
        fixture.write("control.json", repeated);
        fixture.advance(3);
        helper.assertTrue(fixture.coordinator.state().equals("prepared") && !fixture.game.running(),
                "Repeating an acknowledged command ID cannot release a modified roster");
        helper.assertTrue(command.toString().equals(fixture.status().get("commandId").getAsString())
                        && fixture.status().getAsJsonArray("participants").size() == 3,
                "Idempotent replay keeps its original acknowledgment and roster");
        helper.succeed();
    }

    @GameTest
    public void newRoundCannotClaimAlreadyRunningGeneration(GameTestHelper helper) throws IOException {
        var a = player(helper, 1);
        var b = player(helper, 3);
        var fixture = fixture(helper, List.of(a, b));
        fixture.activate();
        String originalRound = fixture.status().get("roundId").getAsString();
        String originalCommand = fixture.status().get("commandId").getAsString();
        fixture.control("prepare", UUID.randomUUID(), List.of(a.getUUID(), b.getUUID()), fixture.generation);
        fixture.advance(3);
        helper.assertTrue(fixture.coordinator.state().equals("running") && fixture.game.running(),
                "A new round cannot reserve a generation already hosting a match");
        helper.assertTrue(originalRound.equals(fixture.status().get("roundId").getAsString())
                        && originalCommand.equals(fixture.status().get("commandId").getAsString()),
                "Rejected reservation leaves the original round and acknowledgment intact");
        helper.succeed();
    }

    private static Fixture fixture(GameTestHelper helper, List<ServerPlayer> players) throws IOException {
        var fixture = new Fixture(helper, players);
        helper.runBeforeTestEnd(() -> {
            fixture.coordinator.close();
            fixture.game.stop("Test cleanup");
            try (var files = Files.walk(fixture.directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
        return fixture;
    }

    private static ServerPlayer player(GameTestHelper helper, int x) {
        var server = helper.getLevel().getServer();
        UUID id = UUID.randomUUID();
        var cookie = CommonListenerCookie.createInitial(new GameProfile(id, "NetSwap" + id.toString().substring(0, 8)), false);
        var player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        helper.runBeforeTestEnd(() -> {
            var current = server.getPlayerList().getPlayer(id);
            if (current != null) { server.getPlayerList().remove(current); current.discard(); }
            channel.close();
            player.discard();
        });
        player.setGameMode(GameType.SURVIVAL);
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(x, 2, 1)));
        player.teleportTo(helper.getLevel(), pos.x, pos.y, pos.z, Set.of(), 0, 0, true);
        return player;
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final UUID generation = UUID.randomUUID();
        final Path directory;
        final Set<UUID> online = new LinkedHashSet<>();
        final DeathSwapGame game;
        final NetworkConfig config;
        NetworkRoundCoordinator coordinator;

        Fixture(GameTestHelper helper, List<ServerPlayer> players) throws IOException {
            this.helper = helper;
            directory = Files.createTempDirectory(helper.getLevel().getServer().getWorldPath(LevelResource.ROOT), "network-test-");
            for (ServerPlayer player : players) online.add(player.getUUID());
            game = new DeathSwapGame(helper.getLevel().getServer(), this::lookup,
                    new SpectatorRecovery(directory.resolve("spectators.txt")));
            config = new NetworkConfig(true, "a", generation, 10, 0, directory);
            coordinator = new NetworkRoundCoordinator(helper.getLevel().getServer(), game, config, this::players);
        }

        ServerPlayer lookup(UUID id) {
            return online.contains(id) ? helper.getLevel().getServer().getPlayerList().getPlayer(id) : null;
        }

        List<ServerPlayer> players() {
            var players = new ArrayList<ServerPlayer>();
            for (UUID id : online) {
                var player = lookup(id);
                if (player != null) players.add(player);
            }
            return players;
        }

        void advance(int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                coordinator.tick();
                game.tick();
            }
        }

        void activate() throws IOException {
            control("activate", UUID.randomUUID(), List.of(), generation);
            advance(3);
            if (!coordinator.state().equals("running") || !game.running()) throw new AssertionError("Network fixture failed to activate");
        }

        UUID control(String action, UUID round, List<UUID> participants, UUID commandGeneration) throws IOException {
            return control(action, round, participants, commandGeneration, 10);
        }

        UUID control(String action, UUID round, List<UUID> participants, UUID commandGeneration, int interval) throws IOException {
            UUID command = UUID.randomUUID();
            var json = identity(commandGeneration);
            json.addProperty("commandId", command.toString());
            json.addProperty("action", action);
            json.addProperty("roundId", round.toString());
            var roster = new JsonArray();
            for (UUID id : participants) roster.add(id.toString());
            json.add("participants", roster);
            json.addProperty("swapIntervalSeconds", interval);
            write("control.json", json);
            return command;
        }

        UUID resetRequest(UUID requestGeneration) throws IOException {
            UUID request = UUID.randomUUID();
            var json = identity(requestGeneration);
            json.addProperty("requestId", request.toString());
            json.addProperty("requestedAt", System.currentTimeMillis());
            write("reset.request.json", json);
            return request;
        }

        private JsonObject identity(UUID objectGeneration) {
            var json = new JsonObject();
            json.addProperty("schemaVersion", 1);
            json.addProperty("serverId", "a");
            json.addProperty("generationId", objectGeneration.toString());
            return json;
        }

        private void write(String name, JsonObject object) throws IOException {
            Path temporary = Files.createTempFile(directory, "command-", ".tmp");
            Files.writeString(temporary, object.toString());
            Files.move(temporary, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }

        JsonObject status() throws IOException {
            return JsonParser.parseString(Files.readString(directory.resolve("status.json"))).getAsJsonObject();
        }
    }
}
