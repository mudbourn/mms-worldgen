package info.mudbourn.mmsworldgen.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import info.mudbourn.mmsworldgen.compat.BwgBiomeCache;
import info.mudbourn.mmsworldgen.compat.BwgBiomeSkip;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(
    targets = "net.potionstudios.biomeswevegone.world.level.levelgen.customterrain.CragGardenExtension",
    remap = false
)
public abstract class BwgCragGardenExtensionMixin {

    @WrapMethod(method = "runCragGardenExtension", remap = false, require = 0)
    private static void mmswg$scope(
        Function<BlockPos, Holder<Biome>> biomeGetter,
        ChunkAccess chunk,
        long worldSeed,
        NormalNoise.NoiseParameters noiseParameters,
        NormalNoise.NoiseParameters cliffSpacingParams,
        Operation<Void> original
    ) {
        BwgBiomeCache.enter();
        try {
            if (BwgBiomeSkip.mayContain(chunk, BwgBiomeSkip.CRAG_GARDENS)) {
                original.call(biomeGetter, chunk, worldSeed, noiseParameters, cliffSpacingParams);
            }
        } finally {
            BwgBiomeCache.exit(false);
        }
    }
}
