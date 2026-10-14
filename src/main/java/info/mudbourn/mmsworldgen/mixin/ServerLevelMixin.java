package info.mudbourn.mmsworldgen.mixin;

import info.mudbourn.mmsworldgen.budget.BudgetRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/ChunkGeneratorStructureState;ensureStructuresGenerated()V"
        )
    )
    private void mmsworldgen$bindBudget(CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        BudgetRegistry.bind(level.dimension().identifier(), state);
    }
}
