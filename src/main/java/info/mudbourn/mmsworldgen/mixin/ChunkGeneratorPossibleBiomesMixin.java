package info.mudbourn.mmsworldgen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorPossibleBiomesMixin {

    @Unique
    private volatile Set<Holder<Biome>> mmswg$possibleBiomes;

    @WrapOperation(
        method = "applyBiomeDecoration",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/BiomeSource;possibleBiomes()Ljava/util/Set;"
        )
    )
    private Set<Holder<Biome>> mmswg$cachedPossibleBiomes(
        BiomeSource source,
        Operation<Set<Holder<Biome>>> original
    ) {
        Set<Holder<Biome>> biomes = mmswg$possibleBiomes;
        if (biomes == null) {
            biomes = original.call(source);
            mmswg$possibleBiomes = biomes;
        }
        return biomes;
    }
}
