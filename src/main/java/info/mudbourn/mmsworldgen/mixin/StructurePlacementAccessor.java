package info.mudbourn.mmsworldgen.mixin;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

@Mixin(StructurePlacement.class)
public interface StructurePlacementAccessor {
    @Accessor("locateOffset")
    Vec3i mmsworldgen$locateOffset();

    @Accessor("frequencyReductionMethod")
    StructurePlacement.FrequencyReductionMethod mmsworldgen$frequencyReductionMethod();

    @Accessor("frequency")
    float mmsworldgen$frequency();

    @Accessor("salt")
    int mmsworldgen$salt();

    @Accessor("exclusionZone")
    Optional<StructurePlacement.ExclusionZone> mmsworldgen$exclusionZone();
}
