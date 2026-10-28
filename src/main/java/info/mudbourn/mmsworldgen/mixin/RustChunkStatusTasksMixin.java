package info.mudbourn.mmsworldgen.mixin;

import info.mudbourn.mmsworldgen.rust.RustShadow;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkStatusTasks.class)
public abstract class RustChunkStatusTasksMixin {

    @Inject(method = "generateNoise", at = @At("RETURN"), cancellable = true)
    private static void mmswg$shadow(
        WorldGenContext context,
        ChunkStep step,
        StaticCache2D<GenerationChunkHolder> cache,
        ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        if (RustShadow.active()) {
            cir.setReturnValue(RustShadow.wrap(cir.getReturnValue(), context.level()));
        }
    }
}
