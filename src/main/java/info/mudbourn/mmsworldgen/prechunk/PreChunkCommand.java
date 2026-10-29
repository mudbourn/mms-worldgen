package info.mudbourn.mmsworldgen.prechunk;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class PreChunkCommand {

    private PreChunkCommand() {
    }

    // Builds /mmswg prechunk.
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("prechunk").executes(context -> {
            CommandSourceStack source = context.getSource();
            if (!PreChunk.enabled()) {
                source.sendSuccess(() -> Component.literal("Predictive chunk loading is disabled."), false);
                return 0;
            }
            for (String line : PreChunk.describe(source.getServer())) {
                source.sendSuccess(() -> Component.literal(line), false);
            }
            return 1;
        });
    }
}
