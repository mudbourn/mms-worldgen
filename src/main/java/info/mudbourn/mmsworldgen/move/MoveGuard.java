package info.mudbourn.mmsworldgen.move;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

public final class MoveGuard {

    private static final double MARGIN = 1.0;

    private MoveGuard() {
    }

    public static boolean enabled() {
        return MmsWorldgen.config != null && MmsWorldgen.config.movement.rejectUnreadyChunks;
    }

    // True when any chunk touched by the move from the entity to the target is not loaded.
    public static boolean touchesUnready(ServerLevel level, Entity entity, double x, double y, double z) {
        ServerChunkCache cache = level.getChunkSource();
        double half = entity.getBbWidth() / 2.0 + MARGIN;
        int minX = Mth.floor(Math.min(entity.getX(), x) - half) >> 4;
        int maxX = Mth.floor(Math.max(entity.getX(), x) + half) >> 4;
        int minZ = Mth.floor(Math.min(entity.getZ(), z) - half) >> 4;
        int maxZ = Mth.floor(Math.max(entity.getZ(), z) + half) >> 4;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (cache.getChunkNow(cx, cz) == null) {
                    return true;
                }
            }
        }
        return false;
    }
}
