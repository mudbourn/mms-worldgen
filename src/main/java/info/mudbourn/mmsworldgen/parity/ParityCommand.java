package info.mudbourn.mmsworldgen.parity;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class ParityCommand {

    private ParityCommand() {
    }

    // Builds /mmswg parity.
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("parity").executes(context -> {
            String text = "parity mode=" + Parity.mode()
                + " checked=" + Parity.CHECKED.get()
                + " matched=" + Parity.MATCHED.get()
                + " mismatched=" + Parity.MISMATCHED.get()
                + " unrecorded=" + Parity.UNRECORDED.get()
                + " recorded=" + Parity.RECORDED.get()
                + " advisory_checked=" + Parity.ADVISORY_CHECKED.get()
                + " advisory_mismatched=" + Parity.ADVISORY_MISMATCHED.get();
            context.getSource().sendSuccess(() -> Component.literal(text), false);
            return 1;
        });
    }
}
