package info.mudbourn.mmsworldgen.mixin;

import java.util.concurrent.locks.StampedLock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "terrablender.worldgen.noise.Area", remap = false)
public abstract class TerraBlenderAreaMixin {

    @Shadow
    @Final
    private long[] keys;

    @Shadow
    @Final
    private int[] values;

    @Shadow
    @Final
    private int mask;

    @Shadow
    @Final
    private StampedLock lock;

    @Shadow
    private native int hash(long key);

    @Shadow
    private native long key(int x, int z);

    @Inject(method = "get", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void mmswg$optimisticGet(int x, int z, CallbackInfoReturnable<Integer> cir) {
        long key = key(x, z);
        int idx = hash(key) & mask;
        long stamp = lock.tryOptimisticRead();
        if (stamp == 0L) {
            return;
        }
        long storedKey = keys[idx];
        int value = values[idx];
        if (storedKey == key && lock.validate(stamp)) {
            cir.setReturnValue(value);
        }
    }
}
