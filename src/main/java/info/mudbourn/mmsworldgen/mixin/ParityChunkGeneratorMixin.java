package info.mudbourn.mmsworldgen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import info.mudbourn.mmsworldgen.parity.ParityTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkGenerator.class)
public abstract class ParityChunkGeneratorMixin {

    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    private void mmswg$traceBegin(
        WorldGenLevel level,
        ChunkAccess chunk,
        StructureManager structures,
        CallbackInfo ci
    ) {
        ParityTrace.begin();
    }

    @WrapOperation(
        method = "applyBiomeDecoration",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;placeWithBiomeCheck(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"
        )
    )
    private boolean mmswg$traceFeature(
        PlacedFeature feature,
        WorldGenLevel level,
        ChunkGenerator generator,
        RandomSource random,
        BlockPos origin,
        Operation<Boolean> original
    ) {
        boolean placed = original.call(feature, level, generator, random, origin);
        ParityTrace.feature(level, feature, placed, random);
        return placed;
    }

    @Inject(method = "applyBiomeDecoration", at = @At("RETURN"))
    private void mmswg$traceEnd(
        WorldGenLevel level,
        ChunkAccess chunk,
        StructureManager structures,
        CallbackInfo ci
    ) {
        ParityTrace.end(level, chunk);
    }
}
