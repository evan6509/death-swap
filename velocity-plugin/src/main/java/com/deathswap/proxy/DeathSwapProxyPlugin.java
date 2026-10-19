package com.deathswap.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Plugin(id = "deathswap-network", name = "Death Swap Network", version = BuildVersion.VERSION,
    description = "Fresh-seed Death Swap round routing and safe backend reset coordination.", authors = {"evan6509"})
public final class DeathSwapProxyPlugin {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private volatile RoundController controller;
    private ScheduledTask task;
    private String lastError;

    @Inject
    public DeathSwapProxyPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy;
        this.logger = logger;
        this.directory = directory;
    }

    @Subscribe
    public void initialize(ProxyInitializeEvent event) {
        try {
            NetworkConfig config = NetworkConfig.load(directory.resolve("config.json"));
            for (String name : config.backendServers.keySet()) {
                if (proxy.getServer(name).isEmpty()) throw new IOException("Backend not registered in velocity.toml: " + name);
            }
            controller = new RoundController(config, directory.resolve("state.json"), new VelocityConnections(), System::currentTimeMillis);
            poll();
            task = proxy.getScheduler().buildTask(this, this::poll).repeat(1, TimeUnit.SECONDS).schedule();
            logger.info("Death Swap network ready; configured backends: {}", config.backendServers.keySet());
        } catch (IOException | RuntimeException e) {
            logger.error("Death Swap network could not initialize; player routing remains closed", e);
        }
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("deathswapnetwork").plugin(this).build(),
            new SimpleCommand() {
                @Override public void execute(Invocation invocation) {
                    if (invocation.arguments().length > 1 || (invocation.arguments().length == 1
                        && !"status".equalsIgnoreCase(invocation.arguments()[0]))) {
                        invocation.source().sendMessage(Component.text("Usage: /deathswapnetwork status"));
                        return;
                    }
                    RoundController current = controller;
                    invocation.source().sendMessage(Component.text(current == null
                        ? "Death Swap network unavailable; check proxy logs and config.json" : current.describe()));
                }
                @Override public List<String> suggest(Invocation invocation) { return List.of("status"); }
            });
    }

    private void poll() {
        RoundController current = controller;
        if (current == null) return;
        try {
            current.tick();
            lastError = null;
        } catch (IOException | RuntimeException e) {
            String description = e.toString();
            if (!description.equals(lastError)) {
                logger.error("Death Swap coordination paused; no unsafe reset will be requested", e);
                lastError = description;
            }
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void initialServer(PlayerChooseInitialServerEvent event) {
        RoundController current = controller;
        var destination = current == null ? java.util.Optional.<String>empty() : current.route();
        event.setInitialServer(destination.flatMap(proxy::getServer).orElse(null));
        if (destination.isEmpty()) {
            event.getPlayer().disconnect(Component.text("Death Swap is preparing a fresh world. Please reconnect shortly."));
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void preConnect(ServerPreConnectEvent event) {
        RoundController current = controller;
        var destination = current == null ? java.util.Optional.<String>empty() : current.route();
        var server = destination.flatMap(proxy::getServer);
        event.setResult(server.map(ServerPreConnectEvent.ServerResult::allowed)
            .orElseGet(ServerPreConnectEvent.ServerResult::denied));
        if (server.isEmpty()) {
            event.getPlayer().sendMessage(Component.text("Death Swap is preparing a fresh world; please wait."));
        }
    }

    @Subscribe
    public void shutdown(ProxyShutdownEvent event) {
        if (task != null) task.cancel();
    }

    private final class VelocityConnections implements RoundController.Connections {
        @Override public boolean registered(String backend) { return proxy.getServer(backend).isPresent(); }
        @Override public Set<UUID> playersOn(String backend) {
            return proxy.getServer(backend).map(server -> server.getPlayersConnected().stream()
                .map(Player::getUniqueId).collect(Collectors.toSet())).orElseGet(Set::of);
        }
        @Override public Set<UUID> onlinePlayers() {
            return proxy.getAllPlayers().stream().map(Player::getUniqueId).collect(Collectors.toSet());
        }
        @Override public String currentServer(UUID player) {
            return proxy.getPlayer(player).flatMap(Player::getCurrentServer)
                .map(server -> server.getServerInfo().getName()).orElse(null);
        }
        @Override public CompletableFuture<Boolean> connect(UUID uuid, String backend) {
            var player = proxy.getPlayer(uuid);
            var server = proxy.getServer(backend);
            if (player.isEmpty() || server.isEmpty()) return CompletableFuture.completedFuture(false);
            return player.get().createConnectionRequest(server.get()).connect()
                .handle((result, error) -> error == null && result != null && result.isSuccessful());
        }
    }
}
