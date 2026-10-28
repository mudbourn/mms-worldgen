package info.mudbourn.mmsworldgen.sched;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

public final class SchedCommand {

    private SchedCommand() {
    }

    // Builds /mmswg sched.
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("sched").executes(context -> {
            CommandSourceStack source = context.getSource();
            if (!Sched.enabled()) {
                source.sendSuccess(() -> Component.literal("Fair chunk scheduling is disabled."), false);
                return 0;
            }
            int lineCount = 0;
            for (ServerLevel level : source.getServer().getAllLevels()) {
                List<String> lines = Sched.state(level).describe();
                for (String line : lines) {
                    String text = level.dimension().identifier() + " " + line;
                    source.sendSuccess(() -> Component.literal(text), false);
                    lineCount++;
                }
            }
            if (lineCount == 0) {
                source.sendSuccess(() -> Component.literal("No players tracked."), false);
            }
            return lineCount;
        });
    }
}
