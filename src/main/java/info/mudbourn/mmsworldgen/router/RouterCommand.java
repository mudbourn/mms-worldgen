package info.mudbourn.mmsworldgen.router;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import info.mudbourn.mmsworldgen.MmsWorldgen;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

public final class RouterCommand {

    private RouterCommand() {
    }

    // Builds /mmswg router dump.
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("router").then(Commands.literal("dump").executes(context -> {
            CommandSourceStack source = context.getSource();
            int written = 0;
            for (ServerLevel level : source.getServer().getAllLevels()) {
                String dimension = level.dimension().identifier().toString();
                try {
                    Path file = RouterDump.write(level);
                    source.sendSuccess(() -> Component.literal(dimension + " -> " + file), false);
                    written++;
                } catch (IOException | RuntimeException e) {
                    MmsWorldgen.LOG.error("Router dump failed for {}", dimension, e);
                    source.sendFailure(Component.literal(dimension + " dump failed: " + e));
                }
            }
            return written;
        }));
    }
}
