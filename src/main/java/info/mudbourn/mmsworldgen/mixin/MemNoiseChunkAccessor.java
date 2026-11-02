package info.mudbourn.mmsworldgen.mixin;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkAccess.class)
public interface MemNoiseChunkAccessor {

    @Accessor("noiseChunk")
    void mmswg$setNoiseChunk(NoiseChunk noiseChunk);
}
