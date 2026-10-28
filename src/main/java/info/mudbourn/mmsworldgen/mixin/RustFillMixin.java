package info.mudbourn.mmsworldgen.mixin;

import info.mudbourn.mmsworldgen.rust.RustOn;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class RustFillMixin {

    @Shadow
    @Final
    private Holder<NoiseGeneratorSettings> settings;

    @Shadow
    protected abstract NoiseChunk createNoiseChunk(
        ChunkAccess chunk,
        StructureManager structureManager,
        Blender blender,
        RandomState randomState
    );

    @Shadow
    protected abstract ChunkAccess doFill(
        Blender blender,
        StructureManager structureManager,
        RandomState randomState,
        ChunkAccess chunk,
        int minCellY,
        int cellCountY
    );

    @Inject(method = "fillFromNoise", at = @At("HEAD"), cancellable = true)
    private void mmswg$fill(
        Blender blender,
        RandomState randomState,
        StructureManager structureManager,
        ChunkAccess chunk,
        CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir
    ) {
        if (!RustOn.active()) {
            return;
        }
        NoiseSettings noise = settings.value().noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        int minCellY = Mth.floorDiv(noise.minY(), noise.getCellHeight());
        int cellCountY = Mth.floorDiv(noise.height(), noise.getCellHeight());
        if (cellCountY <= 0) {
            return;
        }
        NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
        RustOn.Plan plan = RustOn.plan(self, !blender.isEmpty(), chunk);
        if (plan == null) {
            return;
        }

        int top = chunk.getSectionIndex(cellCountY * noise.getCellHeight() - 1 + noise.minY());
        int bottom = chunk.getSectionIndex(noise.minY());
        Function<ChunkAccess, NoiseChunk> noiseChunks =
            created -> createNoiseChunk(created, structureManager, blender, randomState);
        cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
            for (int i = top; i >= bottom; i--) {
                chunk.getSection(i).acquire();
            }
            try {
                return RustOn.fill(
                    plan,
                    chunk,
                    noiseChunks,
                    () -> doFill(blender, structureManager, randomState, chunk, minCellY, cellCountY)
                );
            } finally {
                for (int i = top; i >= bottom; i--) {
                    LevelChunkSection section = chunk.getSection(i);
                    section.release();
                }
            }
        }, Util.backgroundExecutor().forName("wgen_fill_noise")));
    }
}
