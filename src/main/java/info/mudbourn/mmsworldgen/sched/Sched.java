package info.mudbourn.mmsworldgen.sched;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerLevel;

public final class Sched {
    private static final Map<DistanceManager, SchedState> STATES = new HashMap<>();

    private Sched() {
    }

    // Registers the per-level scheduler tick and state cleanup.
    public static void init() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (STATES) {
                STATES.clear();
            }
        });
        ServerTickEvents.END_WORLD_TICK.register(Sched::tick);
    }

    // Reports whether fair scheduling is switched on.
    public static boolean enabled() {
        return MmsWorldgen.config != null && MmsWorldgen.config.scheduling.enabled;
    }

    // Records that a chunk gained or lost player-ticket demand.
    public static void onTicketChange(DistanceManager manager, long chunk, boolean wantsTicket) {
        state(manager).onDemandChange(chunk, wantsTicket);
    }

    // Returns the scheduler state for a level.
    public static SchedState state(ServerLevel level) {
        return state(level.getChunkSource().chunkMap.getDistanceManager());
    }

    private static SchedState state(DistanceManager manager) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(manager, SchedState::new);
        }
    }

    private static void tick(ServerLevel level) {
        if (enabled()) {
            state(level).pump(level);
        }
    }
}
