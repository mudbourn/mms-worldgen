package info.mudbourn.mmsworldgen.mixin;

import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.server.level.DistanceManager$PlayerTicketTracker")
public interface SchedPlayerTicketTrackerAccessor {

    @Accessor("field_17463")
    DistanceManager mmswg$owner();
}
