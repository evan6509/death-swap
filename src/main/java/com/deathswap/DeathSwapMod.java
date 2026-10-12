package com.deathswap;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

public final class DeathSwapMod implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("deathswap");
    private static final Map<MinecraftServer, DeathSwapGame> GAMES = new IdentityHashMap<>();

    @Override
    public void onInitialize() {
        DeathSwapCommands.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try { GAMES.put(server, new DeathSwapGame(server)); }
            catch (IOException e) { LOGGER.error("Cannot read Death Swap recovery data; matches are disabled", e); }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var game = GAMES.get(server);
            if (game != null) game.tick();
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damage) -> {
            if (entity instanceof ServerPlayer player) {
                var game = GAMES.get(player.level().getServer());
                if (game != null) game.eliminate(player, "died");
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var game = GAMES.get(server);
            if (game != null) game.disconnected(handler.player);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var game = GAMES.get(server);
            if (game != null) game.joinedOrRespawned(handler.player);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            var game = GAMES.get(newPlayer.level().getServer());
            if (game != null) game.joinedOrRespawned(newPlayer);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            var game = GAMES.get(server);
            if (game != null) game.stop("Server stopping; match cancelled.");
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(GAMES::remove);
    }

    static DeathSwapGame game(MinecraftServer server) {
        var game = GAMES.get(server);
        if (game == null) throw new IllegalStateException("Death Swap is unavailable; check the server log.");
        return game;
    }
}
