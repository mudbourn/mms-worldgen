package info.mudbourn.mmsworldgen.rust;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import info.mudbourn.mmsworldgen.MmsWorldgen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class RustCommand {

    private RustCommand() {
    }

    // Builds /mmswg rust, showing mode, load state and native version.
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("rust").executes(context -> {
            String status = RustNative.loaded()
                ? "loaded, native " + RustNative.nativeVersion()
                : "not loaded (" + RustNative.failure() + ")";
            String line = "rust.mode=" + MmsWorldgen.config.rust.mode + " library " + status;
            context.getSource().sendSuccess(() -> Component.literal(line), false);
            return RustNative.loaded() ? 1 : 0;
        });
    }
}
