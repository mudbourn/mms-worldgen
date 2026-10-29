package info.mudbourn.mmsworldgen.compat;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

public final class BwgBiomeSkip {

    public static final Identifier CRAG_GARDENS = Identifier.parse("biomeswevegone:crag_gardens");

    public static final Identifier BASALT_BARRERA = Identifier.parse("biomeswevegone:basalt_barrera");

    private static final ThreadLocal<StaticCache2D<GenerationChunkHolder>> CACHE = new ThreadLocal<>();

    // Remembers the surface step's chunk cache for the current thread.
    public static void begin(StaticCache2D<GenerationChunkHolder> cache) {
        CACHE.set(cache);
    }

    // Forgets the surface step's chunk cache.
    public static void end() {
        CACHE.remove();
    }

    // Whether a zoomed lookup in this chunk could return the biome; true when unsure.
    public static boolean mayContain(ChunkAccess chunk, Identifier biome) {
        StaticCache2D<GenerationChunkHolder> cache = CACHE.get();
        if (cache == null || !inVerticalRange(chunk)) {
            return true;
        }
        ChunkPos pos = chunk.getPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!cache.contains(pos.x + dx, pos.z + dz)) {
                    return true;
                }
                ChunkAccess near = cache.get(pos.x + dx, pos.z + dz).getChunkIfPresentUnchecked(ChunkStatus.BIOMES);
                if (near == null || near.getBlendingData() != null || hasBiome(near, biome)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasBiome(ChunkAccess chunk, Identifier biome) {
        for (LevelChunkSection section : chunk.getSections()) {
            if (section.getBiomes().maybeHas(holder -> holder.is(biome))) {
                return true;
            }
        }
        return false;
    }

    private static boolean inVerticalRange(ChunkAccess chunk) {
        int minQuart = chunk.getMinY() >> 2;
        int maxQuart = (chunk.getMinY() + chunk.getHeight() - 1) >> 2;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int y = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) / 10 * 10;
                if (y <= chunk.getMinY()) {
                    continue;
                }
                int quart = (y - 2) >> 2;
                if (quart < minQuart || quart + 1 > maxQuart) {
                    return false;
                }
            }
        }
        return true;
    }

    private BwgBiomeSkip() {
    }
}
