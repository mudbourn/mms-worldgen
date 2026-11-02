package info.mudbourn.mmsworldgen.mixin;

import info.mudbourn.mmsworldgen.diag.ChunkStall;
import java.util.function.BooleanSupplier;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockableEventLoop.class)
public abstract class DiagChunkStallMixin {

    @Inject(method = "managedBlock", at = @At("HEAD"))
    private void mmswg$waitBegin(BooleanSupplier done, CallbackInfo ci) {
        if (ChunkStall.watches(this)) {
            ChunkStall.begin();
        }
    }

    @Inject(method = "managedBlock", at = @At("RETURN"))
    private void mmswg$waitEnd(BooleanSupplier done, CallbackInfo ci) {
        if (ChunkStall.watches(this)) {
            ChunkStall.end();
        }
    }
}
