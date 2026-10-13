package com.deathswap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DeathSwapMod implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("deathswap");
    private static final Map<MinecraftServer, DeathSwapGame> GAMES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, NetworkRoundCoordinator> NETWORKS = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        DeathSwapCommands.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                var game = new DeathSwapGame(server);
                var config = NetworkConfig.load(FabricLoader.getInstance().getConfigDir().resolve("deathswap"));
                if (config.enabled()) NETWORKS.put(server, new NetworkRoundCoordinator(server, game, config));
                GAMES.put(server, game);
            } catch (IOException e) { LOGGER.error("Cannot read Death Swap recovery/network data; matches are disabled", e); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var network = NETWORKS.get(server);
            if (network != null) network.tick();
            var game = GAMES.get(server);
            if (game != null) game.tick();
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damage) -> {
            if (entity instanceof ServerPlayer player) {
                var game = GAMES.get(player.level().getServer());
                var network = NETWORKS.get(player.level().getServer());
                if (network != null) network.onDeath(player);
                else if (game != null) game.eliminate(player, "died");
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var game = GAMES.get(server);
            var network = NETWORKS.get(server);
            if (network != null) network.disconnected(handler.player);
            else if (game != null) game.disconnected(handler.player);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var game = GAMES.get(server);
            var network = NETWORKS.get(server);
            if (network != null) network.joinedOrRespawned(handler.player);
            else if (game != null) game.joinedOrRespawned(handler.player);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            var game = GAMES.get(newPlayer.level().getServer());
            var network = NETWORKS.get(newPlayer.level().getServer());
            if (network != null) network.joinedOrRespawned(newPlayer);
            else if (game != null) game.joinedOrRespawned(newPlayer);
        });
        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
            var network = NETWORKS.get(server);
            if (network != null && network.rejectsJoins()) {
                handler.disconnect(Component.literal("This Death Swap world is resetting. Reconnect through the proxy."));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            var network = NETWORKS.get(server);
            if (network != null) network.close();
            var game = GAMES.get(server);
            if (game != null) game.stop("Server stopping; match cancelled.");
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { GAMES.remove(server); NETWORKS.remove(server); });
    }

    static DeathSwapGame game(MinecraftServer server) {
        var game = GAMES.get(server);
        if (game == null) throw new IllegalStateException("Death Swap is unavailable; check the server log.");
        return game;
    }
}
