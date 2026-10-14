package info.mudbourn.mmsworldgen.mixin;

import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StructureSet.class)
public interface StructureSetAccessor {
    @Accessor("placement")
    StructurePlacement mmsworldgen$placement();
}
