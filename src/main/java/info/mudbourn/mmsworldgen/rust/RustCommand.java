package info.mudbourn.mmsworldgen.rust;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import info.mudbourn.mmsworldgen.MmsWorldgen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class RustCommand {

    private RustCommand() {
    }

    // Builds /mmswg rust with the load state, the density comparison results and compare.
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("rust").then(Commands.literal("compare").executes(context -> {
            if (!RustNative.loaded()) {
                return 0;
            }
            RustCompare.run(context.getSource().getServer());
            RustCompare.status().values().forEach(
                line -> context.getSource().sendSuccess(() -> Component.literal(line), false)
            );
            return 1;
        })).executes(context -> {
            String status = RustNative.loaded()
                ? "loaded, native " + RustNative.nativeVersion()
                : "not loaded (" + RustNative.failure() + ")";
            String line = "rust.mode=" + MmsWorldgen.config.rust.mode + " library " + status + ", " + RustNative.selfTestResult();
            context.getSource().sendSuccess(() -> Component.literal(line), false);
            RustCompare.status().values().forEach(
                dimensionLine -> context.getSource().sendSuccess(() -> Component.literal(dimensionLine), false)
            );
            return RustNative.loaded() ? 1 : 0;
        });
    }
}
