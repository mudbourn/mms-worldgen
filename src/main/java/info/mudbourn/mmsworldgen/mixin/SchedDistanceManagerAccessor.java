package info.mudbourn.mmsworldgen.mixin;

import java.util.concurrent.Executor;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DistanceManager.class)
public interface SchedDistanceManagerAccessor {

    @Accessor("ticketStorage")
    TicketStorage mmswg$ticketStorage();

    @Accessor("mainThreadExecutor")
    Executor mmswg$mainThreadExecutor();
}
