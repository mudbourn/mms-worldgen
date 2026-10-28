package info.mudbourn.mmsworldgen.mixin;

import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkGenerator.class)
public interface ParityChunkGeneratorAccessor {

    @Accessor("featuresPerStep")
    Supplier<List<FeatureSorter.StepFeatureData>> mmswg$featuresPerStep();
}
