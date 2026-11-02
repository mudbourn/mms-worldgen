package info.mudbourn.mmsworldgen.mixin;

import info.mudbourn.mmsworldgen.MmsWorldgen;
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
public abstract class MemNoiseChunkReleaseMixin {

    @Inject(method = "generateCarvers", at = @At("RETURN"), cancellable = true)
    private static void mmswg$releaseNoiseChunk(
        WorldGenContext context,
        ChunkStep step,
        StaticCache2D<GenerationChunkHolder> cache,
        ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        if (!MmsWorldgen.config.memory.releaseNoiseChunks) {
            return;
        }
        cir.setReturnValue(cir.getReturnValue().thenApply(done -> {
            ((MemNoiseChunkAccessor) done).mmswg$setNoiseChunk(null);
            return done;
        }));
    }
}
