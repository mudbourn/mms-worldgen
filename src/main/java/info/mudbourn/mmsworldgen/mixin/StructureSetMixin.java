package info.mudbourn.mmsworldgen.mixin;

import info.mudbourn.mmsworldgen.budget.BudgetRegistry;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureSet.class)
public abstract class StructureSetMixin {
    @Inject(method = "placement", at = @At("HEAD"), cancellable = true)
    private void mmsworldgen$swapPlacement(CallbackInfoReturnable<StructurePlacement> cir) {
        StructurePlacement budget = BudgetRegistry.placementFor((StructureSet) (Object) this);
        if (budget != null) {
            cir.setReturnValue(budget);
        }
    }
}
