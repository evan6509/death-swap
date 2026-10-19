package com.deathswap;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DeathSwapGameTests {
    @GameTest
    public void swapsSnapshotsAndKeepsInventories(GameTestHelper helper) {
        var a = player(helper, 1);
        var b = player(helper, 3);
        Vec3 fromA = a.position(), fromB = b.position();
        a.setYRot(35); b.setYRot(160);
        a.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
        b.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
        a.setDeltaMovement(1, -2, 3);
        a.fallDistance = 30;
        PlayerSwapper.swap(List.of(a, b), Map.of(a.getUUID(), b.getUUID(), b.getUUID(), a.getUUID()));
        helper.assertTrue(a.position().equals(fromB) && b.position().equals(fromA), "Both original snapshots used");
        helper.assertTrue(a.getYRot() == 160 && b.getYRot() == 35, "Facing follows destination");
        helper.assertTrue(a.getInventory().getItem(0).is(Items.DIAMOND)
                && a.getInventory().getItem(0).getCount() == 7, "First inventory kept");
        helper.assertTrue(b.getInventory().getItem(0).is(Items.GOLD_INGOT), "Second inventory kept");
        helper.assertTrue(a.getDeltaMovement().equals(Vec3.ZERO) && a.fallDistance == 0, "Carried fall momentum reset");
        helper.succeed();
    }

    @GameTest
    public void wakesSleepingPlayersBeforeCapturingDestinations(GameTestHelper helper) {
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        }
        var foot = new BlockPos(0, 1, 1);
        var head = foot.east();
        var bed = Blocks.BED.red().defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
        helper.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD));
        var a = player(helper, 1); var b = player(helper, 3);
        Vec3 fromB = b.position();
        a.startSleeping(helper.absolutePos(head));
        helper.assertTrue(a.isSleeping() && helper.getBlockState(head).getValue(BedBlock.OCCUPIED),
                "Participant is sleeping in an occupied bed");
        Vec3 sleepingPosition = a.position();
        Vec3 awakePosition = BedBlock.findStandUpPosition(a.getType(), helper.getLevel(),
                helper.absolutePos(head), Direction.EAST, a.getYRot()).orElseThrow();
        helper.assertTrue(!awakePosition.equals(sleepingPosition), "Wake-up changes the destination position");

        try {
            PlayerSwapper.swap(List.of(a, b), Map.of(a.getUUID(), a.getUUID(), b.getUUID(), a.getUUID()));
            throw new AssertionError("Invalid swap accepted");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(a.isSleeping() && a.position().equals(sleepingPosition)
                    && b.position().equals(fromB) && helper.getBlockState(head).getValue(BedBlock.OCCUPIED),
                    "Rejected swap leaves the sleeper and bed unchanged");
        }

        PlayerSwapper.swap(List.of(a, b), Map.of(a.getUUID(), b.getUUID(), b.getUUID(), a.getUUID()));

        helper.assertTrue(!a.isSleeping() && a.getSleepingPos().isEmpty() && a.getPose() == Pose.STANDING,
                "Sleeping state and pose cleared before the swap finishes");
        helper.assertTrue(!helper.getBlockState(head).getValue(BedBlock.OCCUPIED)
                && !helper.getBlockState(foot).getValue(BedBlock.OCCUPIED), "Both halves of the original bed released");
        helper.assertTrue(a.position().equals(fromB), "Woken participant receives the other original position");
        helper.assertTrue(b.position().equals(awakePosition), "Other participant receives the post-wake position, not the sleep pose");
        helper.succeed();
    }

    @GameTest
    public void threePlayersEachReceiveDistinctLocations(GameTestHelper helper) {
        var a = player(helper, 1); var b = player(helper, 2); var c = player(helper, 3);
        Vec3 fromA = a.position(), fromB = b.position(), fromC = c.position();
        PlayerSwapper.swap(List.of(a, b, c), Map.of(a.getUUID(), b.getUUID(), b.getUUID(), c.getUUID(), c.getUUID(), a.getUUID()));
        helper.assertTrue(a.position().equals(fromB) && b.position().equals(fromC) && c.position().equals(fromA),
                "Three-player cycle uses original positions");
        helper.succeed();
    }

    @GameTest
    public void swapsAcrossDimensions(GameTestHelper helper) {
        var a = player(helper, 1); var b = player(helper, 3);
        var other = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(other != null, "Nether exists");
        helper.assertTrue(b.teleportTo(other, 0.5, 100, 0.5, Set.of(), 90, 0, true), "Setup moves second player to Nether");
        Vec3 fromA = a.position(), fromB = b.position();
        PlayerSwapper.swap(List.of(a, b), Map.of(a.getUUID(), b.getUUID(), b.getUUID(), a.getUUID()));
        helper.assertTrue(a.level() == other && a.position().equals(fromB), "First player receives Nether location");
        helper.assertTrue(b.level() == helper.getLevel() && b.position().equals(fromA), "Second player receives Overworld location");
        a.teleportTo(helper.getLevel(), fromA.x, fromA.y, fromA.z, Set.of(), 0, 0, true);
        helper.succeed();
    }

    @GameTest
    public void invalidSwapDoesNotMoveAnyone(GameTestHelper helper) {
        var a = player(helper, 1); var b = player(helper, 3);
        Vec3 fromA = a.position(), fromB = b.position();
        try {
            PlayerSwapper.swap(List.of(a, b), Map.of(a.getUUID(), a.getUUID(), b.getUUID(), a.getUUID()));
            throw new AssertionError("Duplicate destination accepted");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(a.position().equals(fromA) && b.position().equals(fromB), "Rejected before any teleport");
        }
        helper.succeed();
    }

    @GameTest
    public void timerTriggersSwapAndResets(GameTestHelper helper) throws IOException {
        var a = player(helper, 1); var b = player(helper, 3);
        var game = game(helper, List.of(a, b));
        game.setInterval(10);
        game.start(List.of(a, b));
        Vec3 fromA = a.position(), fromB = b.position();
        for (int tick = 0; tick < 199; tick++) game.tick();
        helper.assertTrue(a.position().equals(fromA), "Timer does not move players early");
        game.tick();
        helper.assertTrue(a.position().equals(fromB) && b.position().equals(fromA), "Timer moves both players");
        helper.assertTrue(game.status().contains("10 seconds") && game.status().contains("Swaps: 1"), "Timer reset after swap");
        game.stop("Test complete");
        helper.succeed();
    }

    @GameTest
    public void eliminatedRespawnSpectatesAndStopRestores(GameTestHelper helper) throws IOException {
        var a = player(helper, 1); var b = player(helper, 2); var c = player(helper, 3);
        var game = game(helper, List.of(a, b, c));
        game.start(List.of(a, b, c));
        a.setHealth(0);
        game.eliminate(a, "died");
        a.setHealth(20);
        game.joinedOrRespawned(a);
        helper.assertTrue(a.gameMode() == GameType.SPECTATOR, "Eliminated player spectates after respawn");
        game.tick();
        helper.assertTrue(game.running() && game.status().contains("2 players remain"), "Two remaining players continue");
        game.stop("Test stopped");
        helper.assertTrue(a.gameMode() == GameType.SURVIVAL && !game.running(), "Stop restores survival");
        helper.succeed();
    }

    @GameTest
    public void disconnectEndsMatchAndWinnerStaysSurvival(GameTestHelper helper) throws IOException {
        var a = player(helper, 1); var b = player(helper, 3);
        var game = game(helper, List.of(a, b));
        game.start(List.of(a, b));
        game.disconnected(a);
        game.tick();
        helper.assertTrue(!game.running(), "Last survivor ends the match");
        helper.assertTrue(a.gameMode() == GameType.SURVIVAL && b.gameMode() == GameType.SURVIVAL,
                "All online players restored at match end");
        helper.succeed();
    }

    @GameTest
    public void simultaneousDeathsEndWithoutWinner(GameTestHelper helper) throws IOException {
        var a = player(helper, 1); var b = player(helper, 3);
        var game = game(helper, List.of(a, b));
        game.start(List.of(a, b));
        a.setHealth(0); b.setHealth(0);
        game.eliminate(a, "died"); game.eliminate(b, "died");
        helper.assertTrue(game.running(), "Finish is deferred until end of tick");
        game.tick();
        helper.assertTrue(!game.running(), "All players eliminated ends the match");
        helper.succeed();
    }

    @GameTest
    public void reconnectAfterRestartRestoresManagedSpectator(GameTestHelper helper) throws IOException {
        var a = player(helper, 1); var b = player(helper, 2); var c = player(helper, 3);
        var file = recoveryFile(helper);
        Map<UUID, ServerPlayer> online = new HashMap<>();
        for (var player : List.of(a, b, c)) online.put(player.getUUID(), player);
        var recovery = new SpectatorRecovery(file);
        var game = new DeathSwapGame(helper.getLevel().getServer(), online::get, recovery);
        game.start(List.of(a, b, c));
        game.eliminate(a, "died");
        online.remove(a.getUUID());
        game.stop("Test stopped");
        helper.assertTrue(a.gameMode() == GameType.SPECTATOR && recovery.contains(a.getUUID()), "Offline player retained for recovery");
        var restarted = new DeathSwapGame(helper.getLevel().getServer(), online::get, new SpectatorRecovery(file));
        online.put(a.getUUID(), a);
        restarted.joinedOrRespawned(a);
        helper.assertTrue(a.gameMode() == GameType.SURVIVAL && new SpectatorRecovery(file).pending().isEmpty(),
                "Persisted recovery restores player after restart");
        helper.succeed();
    }

    @GameTest
    public void registeredEventsHandleDeathRespawnAndDisconnect(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var a = player(helper, 1); var b = player(helper, 2); var c = player(helper, 3);
        var game = DeathSwapMod.game(server);
        try {
            game.start(List.of(a, b, c));
            // Exercise the real death path without newly connected players' spawn protection.
            a.setHealth(0);
            a.die(a.damageSources().genericKill());
            helper.assertTrue(game.status().contains("2 players remain"), "Registered death hook eliminates player");
            var respawned = server.getPlayerList().respawn(a, false, Entity.RemovalReason.KILLED);
            helper.assertTrue(respawned.gameMode() == GameType.SPECTATOR, "Registered respawn hook sets spectator mode");
            // Drive the public Fabric callback, then vanilla's disconnect cleanup.
            ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(b.connection, server);
            helper.assertTrue(game.status().contains("1 players remain"), "Registered disconnect hook eliminates player");
            helper.assertTrue(b.gameMode() == GameType.SURVIVAL, "Disconnect hook does not change mode or send packets");
            b.connection.onDisconnect(new DisconnectionDetails(Component.literal("Test disconnect")));
            ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
            helper.assertTrue(!game.running(), "Registered tick hook resolves the winner");
            helper.assertTrue(respawned.gameMode() == GameType.SURVIVAL && c.gameMode() == GameType.SURVIVAL,
                    "Match end restores the respawned player and keeps winner in survival");
            server.getPlayerList().remove(respawned);
        } finally { game.stop("Test cleanup"); }
        helper.succeed();
    }

    @GameTest
    public void startsWithListedNamesAndPreservesSelectorSupport(GameTestHelper helper) throws Exception {
        var restore = isolateCommandPlayers(helper);
        var a = commandPlayer(helper, 1); var b = commandPlayer(helper, 3); var c = commandPlayer(helper, 5);
        var server = helper.getLevel().getServer();
        var game = DeathSwapMod.game(server);
        var dispatcher = server.getCommands().getDispatcher();
        var source = a.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
        String names = a.getPlainTextName() + " " + b.getPlainTextName();
        try {
            helper.assertTrue(dispatcher.execute("deathswap start " + names, source) == 1,
                    "Two space-separated names start a match");
            helper.assertTrue(game.isActiveParticipant(a.getUUID()) && game.isActiveParticipant(b.getUUID())
                    && !game.isActiveParticipant(c.getUUID()), "Only the listed players participate");
            game.stop("Test cleanup");
            helper.assertTrue(dispatcher.execute("deathswap start " + names + " " + c.getPlainTextName()
                    + " " + a.getPlainTextName(), source) == 1, "Longer lists allow repeated names");
            helper.assertTrue(game.isActiveParticipant(c.getUUID()) && game.status().startsWith("3 players remain"),
                    "Each selected player is included once");
            game.stop("Test cleanup");
            helper.assertTrue(dispatcher.execute("deathswap start " + names + " MissingPlayer", source) == 0
                    && !game.running(), "Unknown names do not partially start a match");
            helper.assertTrue(dispatcher.execute("deathswap start @a[gamemode=survival]", source) == 1,
                    "Existing selector syntax still starts a match");
            for (String prefix : List.of("deathswap start " + a.getPlainTextName() + " Cmd",
                    "deathswap start " + names + " Cmd")) {
                var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(prefix, source)).get();
                helper.assertTrue(suggestions.getList().stream().anyMatch(s -> s.getText().equals(c.getPlainTextName())),
                        "Additional player names have tab completion");
            }
        } finally {
            game.stop("Test cleanup");
            restore.run();
        }
        helper.succeed();
    }

    @GameTest
    public void exceptStartsWithOtherEligiblePlayersOnly(GameTestHelper helper) throws Exception {
        var restore = isolateCommandPlayers(helper);
        var a = commandPlayer(helper, 1); var b = commandPlayer(helper, 3); var excluded = commandPlayer(helper, 5);
        var creative = commandPlayer(helper, 7);
        creative.setGameMode(GameType.CREATIVE);
        var server = helper.getLevel().getServer();
        var game = DeathSwapMod.game(server);
        var dispatcher = server.getCommands().getDispatcher();
        var source = a.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
        Vec3 excludedPosition = excluded.position();
        try {
            helper.assertTrue(dispatcher.execute("deathswap except " + excluded.getPlainTextName(), source) == 1,
                    "Except starts a match immediately");
            helper.assertTrue(game.isActiveParticipant(a.getUUID()) && game.isActiveParticipant(b.getUUID())
                    && !game.isActiveParticipant(excluded.getUUID()) && !game.isActiveParticipant(creative.getUUID()),
                    "Only other living survival players participate");
            game.swapNow();
            helper.assertTrue(excluded.position().equals(excludedPosition) && excluded.gameMode() == GameType.SURVIVAL,
                    "Excluded player is neither swapped nor forced into spectator mode");
            game.stop("Test cleanup");
            helper.assertTrue(dispatcher.execute("deathswap start", source) == 1
                    && game.isActiveParticipant(excluded.getUUID()), "Exclusion does not carry over to later matches");
        } finally {
            game.stop("Test cleanup");
            restore.run();
        }
        helper.succeed();
    }

    @GameTest
    public void exceptRejectsTooFewPlayersUnknownNamesAndManagedRounds(GameTestHelper helper) throws Exception {
        var restore = isolateCommandPlayers(helper);
        var a = commandPlayer(helper, 1); var b = commandPlayer(helper, 3);
        var server = helper.getLevel().getServer();
        var game = DeathSwapMod.game(server);
        var dispatcher = server.getCommands().getDispatcher();
        var source = a.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
        try {
            helper.assertTrue(dispatcher.execute("deathswap except " + a.getPlainTextName(), source) == 0
                    && !game.running(), "Excluding one of two players cannot start a match");
            try {
                dispatcher.execute("deathswap except MissingPlayer", source);
                throw new AssertionError("Unknown excluded player accepted");
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { }
            helper.assertTrue(!game.running(), "Unknown excluded player does not start a match");
            commandPlayer(helper, 5);
            game.setManagedNetwork(true);
            helper.assertTrue(dispatcher.execute("deathswap except " + a.getPlainTextName(), source) == 0,
                    "Except cannot override managed rounds");
            helper.assertTrue(dispatcher.execute("deathswap start " + a.getPlainTextName() + " " + b.getPlainTextName(),
                    source) == 0 && !game.running(), "Listed names cannot override managed rounds");
        } finally {
            game.setManagedNetwork(false);
            game.stop("Test cleanup");
            restore.run();
        }
        helper.succeed();
    }

    @GameTest
    public void commandsRequireOperatorPermission(GameTestHelper helper) throws Exception {
        var player = player(helper, 1);
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        var operator = player.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
        var denied = player.createCommandSourceStack().withPermission(PermissionSet.NO_PERMISSIONS);
        helper.assertTrue(dispatcher.execute("deathswap status", denied) == 1, "Everyone can inspect status");
        for (String command : List.of("deathswap start", "deathswap start DeathSwapTest1 DeathSwapTest3",
                "deathswap except DeathSwapTest1", "deathswap stop", "deathswap swap", "deathswap interval 10")) {
            try {
                dispatcher.execute(command, denied);
                throw new AssertionError("Non-operator command allowed: " + command);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { }
        }
        helper.assertTrue(dispatcher.execute("deathswap interval 10", operator) == 1, "Operator can change interval");
        dispatcher.execute("deathswap interval 300", operator);
        helper.succeed();
    }

    // Existing GameTests can still have mock players online while another test runs.
    // Keep command tests' automatic selection independent of those fixtures.
    private static Runnable isolateCommandPlayers(GameTestHelper helper) {
        Map<ServerPlayer, GameType> modes = new HashMap<>();
        for (var player : helper.getLevel().getServer().getPlayerList().getPlayers()) {
            modes.put(player, player.gameMode());
            player.setGameMode(GameType.CREATIVE);
        }
        return () -> modes.forEach(ServerPlayer::setGameMode);
    }

    private static ServerPlayer commandPlayer(GameTestHelper helper, int x) {
        return player(helper, x, "Cmd" + x + UUID.randomUUID().toString().substring(0, 8));
    }

    private static ServerPlayer player(GameTestHelper helper, int x) {
        return player(helper, x, "DeathSwapTest" + x);
    }

    private static ServerPlayer player(GameTestHelper helper, int x, String name) {
        var server = helper.getLevel().getServer();
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        var player = new ServerPlayer(server, helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        helper.runBeforeTestEnd(() -> {
            server.getPlayerList().remove(player);
            channel.close();
            player.discard();
        });
        player.setGameMode(GameType.SURVIVAL);
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(x, 2, 1)));
        player.teleportTo(helper.getLevel(), pos.x, pos.y, pos.z, Set.of(), 0, 0, true);
        return player;
    }

    private static DeathSwapGame game(GameTestHelper helper, List<ServerPlayer> players) throws IOException {
        Map<UUID, ServerPlayer> online = new HashMap<>();
        for (var player : players) online.put(player.getUUID(), player);
        return new DeathSwapGame(helper.getLevel().getServer(), online::get, new SpectatorRecovery(recoveryFile(helper)));
    }

    private static java.nio.file.Path recoveryFile(GameTestHelper helper) {
        var file = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("test-spectators-" + UUID.randomUUID() + ".txt");
        helper.runBeforeTestEnd(() -> {
            try { Files.deleteIfExists(file); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        });
        return file;
    }
}
