package info.mudbourn.mmsworldgen.sched;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.Ticket;

final class PlayerBudget {
    static final double MOVING_SPEED = 2.0;
    private static final double TELEPORT_SPEED = 250.0;
    private static final double SMOOTHING_SECONDS = 0.5;
    private static final double MAX_TRACK_SECONDS = 2.0;

    final Long2ObjectOpenHashMap<Prefetch> prefetched = new Long2ObjectOpenHashMap<>();
    double generateTokens;
    double loadTokens;
    double prefetchTokens;
    double prefetchLoadTokens;
    double lastX;
    double lastZ;
    double velocityX;
    double velocityZ;
    boolean positioned;
    int queued;
    int generating;
    int prefetchGenerating;
    long prefetchIssued;
    String name = "";

    PlayerBudget(double generateTokens, double loadTokens, double prefetchTokens, double prefetchLoadTokens) {
        this.generateTokens = generateTokens;
        this.loadTokens = loadTokens;
        this.prefetchTokens = prefetchTokens;
        this.prefetchLoadTokens = prefetchLoadTokens;
    }

    // Updates the smoothed horizontal velocity in blocks per second and returns true on a teleport.
    boolean track(double x, double z, double seconds) {
        boolean teleported = false;
        if (positioned && seconds > 0.001 && seconds <= MAX_TRACK_SECONDS) {
            double dx = x - lastX;
            double dz = z - lastZ;
            if (Math.sqrt(dx * dx + dz * dz) / seconds > TELEPORT_SPEED) {
                velocityX = 0;
                velocityZ = 0;
                teleported = true;
            } else {
                double alpha = Math.min(1.0, seconds / SMOOTHING_SECONDS);
                velocityX += (dx / seconds - velocityX) * alpha;
                velocityZ += (dz / seconds - velocityZ) * alpha;
            }
        }
        lastX = x;
        lastZ = z;
        positioned = true;
        return teleported;
    }

    // Returns the horizontal speed in blocks per second.
    double speed() {
        return Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
    }

    // Tells whether the player moves fast enough to have a heading.
    boolean moving() {
        return speed() > MOVING_SPEED;
    }

    record Prefetch(Ticket ticket, long tick) {
    }
}
