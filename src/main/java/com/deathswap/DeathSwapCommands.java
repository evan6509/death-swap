package com.deathswap;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.GameType;

final class DeathSwapCommands {
    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            var root = Commands.literal("deathswap")
                    .executes(DeathSwapCommands::status)
                    .then(Commands.literal("status").executes(DeathSwapCommands::status));
            root.then(Commands.literal("start").requires(DeathSwapCommands::operator)
                    .executes(context -> execute(context, game -> game.start(
                            context.getSource().getServer().getPlayerList().getPlayers().stream()
                                    .filter(p -> p.isAlive() && p.gameMode() == GameType.SURVIVAL).toList())))
                    .then(Commands.argument("players", EntityArgument.players()).executes(context -> {
                        var players = EntityArgument.getPlayers(context, "players");
                        return execute(context, game -> game.start(players));
                    })));
            root.then(Commands.literal("stop").requires(DeathSwapCommands::operator)
                    .executes(context -> execute(context, game -> {
                        if (!game.running()) throw new IllegalStateException("No Death Swap match is running.");
                        game.stop("Match stopped by an operator.");
                    })));
            root.then(Commands.literal("swap").requires(DeathSwapCommands::operator)
                    .executes(context -> execute(context, DeathSwapGame::swapNow)));
            root.then(Commands.literal("interval").requires(DeathSwapCommands::operator)
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(10, 86400))
                            .executes(context -> execute(context, game -> {
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
}
