package info.mudbourn.mmsworldgen.mixin;

import info.mudbourn.mmsworldgen.compat.BwgBiomeSkip;
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

@Mixin(value = ChunkStatusTasks.class, priority = 2000)
public abstract class BwgSurfaceScopeMixin {

    @Inject(method = "generateSurface", at = @At("HEAD"))
    private static void mmswg$begin(
        WorldGenContext context,
        ChunkStep step,
        StaticCache2D<GenerationChunkHolder> cache,
        ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        BwgBiomeSkip.begin(cache);
    }

    @Inject(method = "generateSurface", at = @At("RETURN"))
    private static void mmswg$end(
        WorldGenContext context,
        ChunkStep step,
        StaticCache2D<GenerationChunkHolder> cache,
        ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        BwgBiomeSkip.end();
    }
}
