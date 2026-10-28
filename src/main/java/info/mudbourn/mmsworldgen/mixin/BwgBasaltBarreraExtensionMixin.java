package info.mudbourn.mmsworldgen.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import info.mudbourn.mmsworldgen.compat.BwgBiomeCache;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(
    targets = "net.potionstudios.biomeswevegone.world.level.levelgen.customterrain.BasaltBarreraExtension",
    remap = false
)
public abstract class BwgBasaltBarreraExtensionMixin {

    @WrapMethod(method = "runBasaltBarreraExtension", remap = false, require = 0)
    private static void mmswg$scope(
        Function<BlockPos, Holder<Biome>> biomeGetter,
        ChunkAccess chunk,
        WorldGenRegion region,
        ChunkGenerator generator,
        Operation<Void> original
    ) {
        BwgBiomeCache.enter();
        try {
            original.call(biomeGetter, chunk, region, generator);
        } finally {
            BwgBiomeCache.exit(true);
        }
    }
}
