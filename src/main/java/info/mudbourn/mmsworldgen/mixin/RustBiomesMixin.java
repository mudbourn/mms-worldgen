package info.mudbourn.mmsworldgen.mixin;

import info.mudbourn.mmsworldgen.rust.RustBiomes;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.util.Util;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NoiseBasedChunkGenerator.class, priority = 1500)
public abstract class RustBiomesMixin {

    @Shadow
    protected abstract void doCreateBiomes(
        Blender blender,
        RandomState randomState,
        StructureManager structureManager,
        ChunkAccess chunk
    );

    @Inject(method = "createBiomes", at = @At("HEAD"), cancellable = true)
    private void mmswg$biomes(
        RandomState randomState,
        Blender blender,
        StructureManager structureManager,
        ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        RustBiomes.Plan plan = RustBiomes.onPlan((NoiseBasedChunkGenerator) (Object) this, blender, chunk);
        if (plan == null) {
            return;
        }
        cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
            if (!RustBiomes.fill(plan, chunk)) {
                doCreateBiomes(blender, randomState, structureManager, chunk);
            }
            return chunk;
        }, Util.backgroundExecutor().forName("init_biomes")));
    }

    @Inject(method = "createBiomes", at = @At("RETURN"), cancellable = true)
    private void mmswg$shadow(
        RandomState randomState,
        Blender blender,
        StructureManager structureManager,
        ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        cir.setReturnValue(
            RustBiomes.shadow(
                (NoiseBasedChunkGenerator) (Object) this,
                randomState.sampler(),
                blender,
                chunk,
                cir.getReturnValue()
            )
        );
    }

    @ModifyArg(
        method = "createBiomes",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;supplyAsync("
                + "Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)"
                + "Ljava/util/concurrent/CompletableFuture;"
        ),
        index = 0,
        require = 0
    )
    private Supplier<ChunkAccess> mmswg$time(Supplier<ChunkAccess> biomes) {
        return RustBiomes.timed(biomes);
    }
}
