package info.mudbourn.mmsworldgen.mixin;

import info.mudbourn.mmsworldgen.sched.Sched;
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.DistanceManager$PlayerTicketTracker")
public abstract class SchedPlayerTicketTrackerMixin {

    @Inject(method = "onLevelChange(JIZZ)V", at = @At("HEAD"), cancellable = true)
    private void mmswg$schedule(
        long chunk,
        int level,
        boolean hadTicket,
        boolean wantsTicket,
        CallbackInfo ci
    ) {
        if (!Sched.enabled()) {
            return;
        }
        DistanceManager owner = ((SchedPlayerTicketTrackerAccessor) (Object) this).mmswg$owner();
        Sched.onTicketChange(owner, chunk, wantsTicket);
        ci.cancel();
    }
}
