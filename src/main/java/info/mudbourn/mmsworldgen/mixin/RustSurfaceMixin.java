package info.mudbourn.mmsworldgen.mixin;

import info.mudbourn.mmsworldgen.rust.RustSurface;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = NoiseBasedChunkGenerator.class, priority = 1500)
public abstract class RustSurfaceMixin {

    @Inject(
        method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void mmswg$surface(
        WorldGenRegion region,
        StructureManager structureManager,
        RandomState randomState,
        ChunkAccess chunk,
        CallbackInfo ci
    ) {
        if (RustSurface.handle((NoiseBasedChunkGenerator) (Object) this, region, chunk)) {
            ci.cancel();
        }
    }

    @Inject(
        method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
        at = @At("RETURN")
    )
    private void mmswg$surfaceCompare(
        WorldGenRegion region,
        StructureManager structureManager,
        RandomState randomState,
        ChunkAccess chunk,
        CallbackInfo ci
    ) {
        RustSurface.finish(chunk);
    }
}
