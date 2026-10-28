package info.mudbourn.mmsworldgen.mixin;

import info.mudbourn.mmsworldgen.compat.BwgBiomeCache;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BiomeManager.class)
public abstract class BwgBiomeManagerMixin {

    @WrapOperation(
        method = "getBiome",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/BiomeManager$NoiseBiomeSource;"
                + "getNoiseBiome(III)Lnet/minecraft/core/Holder;"
        ),
        require = 0
    )
    private Holder<Biome> mmswg$cachedNoiseBiome(
        BiomeManager.NoiseBiomeSource source,
        int x,
        int y,
        int z,
        Operation<Holder<Biome>> original
    ) {
        return BwgBiomeCache.lookup(source, x, y, z, original);
    }
}
