package info.mudbourn.mmsworldgen.mixin;

import info.mudbourn.mmsworldgen.rust.RustShadow;
import java.util.function.Supplier;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class RustFillTimingMixin {

    @ModifyArg(
        method = "fillFromNoise",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;supplyAsync("
                + "Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)"
                + "Ljava/util/concurrent/CompletableFuture;"
        ),
        index = 0,
        require = 0
    )
    private Supplier<ChunkAccess> mmswg$time(Supplier<ChunkAccess> fill) {
        return RustShadow.timed(fill);
    }
}
