package com.deathswap;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.GameType;

import java.util.LinkedHashSet;
import java.util.List;

final class DeathSwapCommands {
    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            var root = Commands.literal("deathswap")
                    .executes(DeathSwapCommands::status)
                    .then(Commands.literal("status").executes(DeathSwapCommands::status));
            root.then(Commands.literal("start").requires(DeathSwapCommands::operator)
                    .executes(context -> executeStandalone(context, game -> game.start(
                            eligiblePlayers(context.getSource()))))
                    .then(Commands.argument("players", EntityArgument.players())
                            .executes(context -> startSelected(context, false))
                            .then(Commands.argument("additionalPlayers", StringArgumentType.greedyString())
                                    .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                            context.getSource().getOnlinePlayerNames(),
                                            builder.createOffset(builder.getInput().lastIndexOf(' ') + 1)))
                                    .executes(context -> startSelected(context, true)))));
            root.then(Commands.literal("except").requires(DeathSwapCommands::operator)
                    .then(Commands.argument("excluded", EntityArgument.player()).executes(context -> {
                        var excluded = EntityArgument.getPlayer(context, "excluded");
                        return executeStandalone(context, game -> game.start(eligiblePlayers(context.getSource())
                                .stream().filter(player -> !player.getUUID().equals(excluded.getUUID())).toList()));
                    })));
            root.then(Commands.literal("stop").requires(DeathSwapCommands::operator)
                    .executes(context -> executeStandalone(context, game -> {
                        if (!game.running()) throw new IllegalStateException("No Death Swap match is running.");
                        game.stop("Match stopped by an operator.");
                    })));
            root.then(Commands.literal("swap").requires(DeathSwapCommands::operator)
                    .executes(context -> execute(context, DeathSwapGame::swapNow)));
            root.then(Commands.literal("interval").requires(DeathSwapCommands::operator)
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(10, 86400))
                            .executes(context -> executeStandalone(context, game -> {
                                int seconds = IntegerArgumentType.getInteger(context, "seconds");
                                game.setInterval(seconds);
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "Death Swap interval set to " + seconds + " seconds."), true);
                            }))));
            dispatcher.register(root);
        });
    }

    private static boolean operator(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static List<ServerPlayer> eligiblePlayers(CommandSourceStack source) {
        return source.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> player.isAlive() && player.gameMode() == GameType.SURVIVAL).toList();
    }

    private static int startSelected(CommandContext<CommandSourceStack> context, boolean additional)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var players = new LinkedHashSet<>(EntityArgument.getPlayers(context, "players"));
        return executeStandalone(context, game -> {
            if (additional) {
                for (String name : StringArgumentType.getString(context, "additionalPlayers").trim().split("\\s+")) {
                    var player = context.getSource().getServer().getPlayerList().getPlayerByName(name);
                    if (player == null) throw new IllegalArgumentException("No online player named " + name + ".");
                    players.add(player);
                }
            }
            game.start(players);
        });
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        return execute(context, game -> context.getSource().sendSuccess(
                () -> Component.literal(game.status()), false));
    }

    private static int execute(CommandContext<CommandSourceStack> context,
                               java.util.function.Consumer<DeathSwapGame> action) {
        try {
            action.accept(DeathSwapMod.game(context.getSource().getServer()));
            return 1;
        } catch (IllegalArgumentException | IllegalStateException e) {
            context.getSource().sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private static int executeStandalone(CommandContext<CommandSourceStack> context,
                                         java.util.function.Consumer<DeathSwapGame> action) {
        return execute(context, game -> {
            if (game.isManagedNetwork()) {
                throw new IllegalStateException("Docker rounds start automatically. Configure their timing in .env and restart the stack.");
            }
            action.accept(game);
        });
    }
}
