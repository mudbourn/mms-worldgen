package info.mudbourn.mmsworldgen.mixin;

import info.mudbourn.mmsworldgen.parity.Parity;
import info.mudbourn.mmsworldgen.parity.ParityStage;
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
public abstract class ParityChunkStatusTasksMixin {

    @Inject(method = "generateNoise", at = @At("RETURN"), cancellable = true)
    private static void mmswg$noise(
        WorldGenContext context,
        ChunkStep step,
        StaticCache2D<GenerationChunkHolder> cache,
        ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        mmswg$hook(context, ParityStage.NOISE, cir);
    }

    @Inject(method = "generateSurface", at = @At("RETURN"), cancellable = true)
    private static void mmswg$surface(
        WorldGenContext context,
        ChunkStep step,
        StaticCache2D<GenerationChunkHolder> cache,
        ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        mmswg$hook(context, ParityStage.SURFACE, cir);
    }

    @Inject(method = "generateCarvers", at = @At("RETURN"), cancellable = true)
    private static void mmswg$carvers(
        WorldGenContext context,
        ChunkStep step,
        StaticCache2D<GenerationChunkHolder> cache,
        ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        mmswg$hook(context, ParityStage.CARVERS, cir);
    }

    @Inject(method = "light", at = @At("RETURN"), cancellable = true)
    private static void mmswg$light(
        WorldGenContext context,
        ChunkStep step,
        StaticCache2D<GenerationChunkHolder> cache,
        ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        mmswg$hook(context, ParityStage.FEATURES, cir);
    }

    private static void mmswg$hook(
        WorldGenContext context,
        ParityStage stage,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        if (Parity.active()) {
            cir.setReturnValue(Parity.wrap(cir.getReturnValue(), context.level(), stage));
        }
    }
}
