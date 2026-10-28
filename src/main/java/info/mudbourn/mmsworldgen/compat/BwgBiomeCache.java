package info.mudbourn.mmsworldgen.compat;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

public final class BwgBiomeCache {

    private static final int MAX_ENTRIES = 65536;

    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private static final class State {
        int depth;
        BiomeManager.NoiseBiomeSource source;
        final Long2ObjectOpenHashMap<Holder<Biome>> biomes = new Long2ObjectOpenHashMap<>();
    }

    // Opens a caching scope on the current thread.
    public static void enter() {
        State state = STATE.get();
        if (state == null) {
            state = new State();
            STATE.set(state);
        }
        state.depth++;
    }

    // Closes a caching scope, dropping the cache when the last pass of the chunk ends.
    public static void exit(boolean release) {
        State state = STATE.get();
        if (state == null) {
            return;
        }
        state.depth--;
        if (state.depth <= 0) {
            if (release) {
                STATE.remove();
            } else {
                state.depth = 0;
            }
        }
    }

    // Wraps a source so its lookups go through the cache.
    public static BiomeManager.NoiseBiomeSource wrap(BiomeManager.NoiseBiomeSource source) {
        return (x, y, z) -> lookup(source, x, y, z);
    }

    // Resolves a noise biome, memoized per source while a scope is open.
    public static Holder<Biome> lookup(BiomeManager.NoiseBiomeSource source, int x, int y, int z) {
        State state = STATE.get();
        if (state == null || state.depth == 0) {
            return source.getNoiseBiome(x, y, z);
        }
        if (state.source != source) {
            state.source = source;
            state.biomes.clear();
        }
        long key = BlockPos.asLong(x, y, z);
        Holder<Biome> biome = state.biomes.get(key);
        if (biome == null) {
            biome = source.getNoiseBiome(x, y, z);
            if (state.biomes.size() >= MAX_ENTRIES) {
                state.biomes.clear();
            }
            state.biomes.put(key, biome);
        }
        return biome;
    }

    private BwgBiomeCache() {
    }
}
