package info.mudbourn.mmsworldgen.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import info.mudbourn.mmsworldgen.parity.ParityCommand;
import info.mudbourn.mmsworldgen.router.RouterCommand;
import info.mudbourn.mmsworldgen.sched.SchedCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class WorldgenCommand {

    private WorldgenCommand() {
    }

    // Registers /mmswg with each phase's subcommands.
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mmswg")
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        root.then(SchedCommand.build());
        root.then(ParityCommand.build());
        root.then(RouterCommand.build());
        dispatcher.register(root);
    }
}
