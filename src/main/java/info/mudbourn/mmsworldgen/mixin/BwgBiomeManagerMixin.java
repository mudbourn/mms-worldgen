package info.mudbourn.mmsworldgen.mixin;

import info.mudbourn.mmsworldgen.compat.BwgBiomeCache;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(BiomeManager.class)
public abstract class BwgBiomeManagerMixin {

    @ModifyVariable(
        method = "withDifferentSource",
        at = @At("HEAD"),
        argsOnly = true,
        require = 0
    )
    private BiomeManager.NoiseBiomeSource mmswg$cachedSource(BiomeManager.NoiseBiomeSource source) {
        return BwgBiomeCache.wrap(source);
    }
}
