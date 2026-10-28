package info.mudbourn.mmsworldgen.parity;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

// Per-chunk trace of decoration: id hash, placed flag and a post-feature random draw for every placed feature.
public final class ParityTrace {
    private static final int ENTRY = 3;
    private static final ThreadLocal<LongArrayList> CURRENT = new ThreadLocal<>();
    private static final Map<Long, String> NAMES = new ConcurrentHashMap<>();

    private ParityTrace() {
    }

    // Starts a trace for the chunk about to be decorated.
    public static void begin() {
        CURRENT.set(Parity.active() ? new LongArrayList() : null);
    }

    // Appends one feature with a draw from its random, which is reseeded before the next one.
    public static void feature(WorldGenLevel level, PlacedFeature feature, boolean placed, RandomSource random) {
        LongArrayList trace = CURRENT.get();
        if (trace == null) {
            return;
        }
        Identifier id = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getKey(feature);
        String name = id == null ? "inline" : id.toString();
        long hash = nameHash(name);
        NAMES.putIfAbsent(hash, name);
        trace.add(hash);
        trace.add(placed ? 1L : 0L);
        trace.add(random.nextLong());
    }

    // Ends the trace and hands it to parity.
    public static void end(WorldGenLevel level, ChunkAccess chunk) {
        LongArrayList trace = CURRENT.get();
        CURRENT.remove();
        if (trace == null) {
            return;
        }
        String dimension = ParityWriter.dimensionName(level.getLevel().dimension().identifier().toString());
        Parity.observe(dimension, ParityStage.FEATURE_TRACE, chunk.getPos().x, chunk.getPos().z, trace.toLongArray());
    }

    // Logs the first feature whose entry differs from the recorded trace.
    static void logDivergence(String dimension, int x, int z, long[] expected, long[] actual, int index) {
        int entry = index / ENTRY;
        MmsWorldgen.LOG.warn(
            "Parity trace divergence dimension={} chunk=({}, {}) entry={} recorded={} current={}",
            dimension,
            x,
            z,
            entry,
            describe(expected, entry),
            describe(actual, entry)
        );
    }

    private static String describe(long[] trace, int entry) {
        int base = entry * ENTRY;
        if (base + ENTRY > trace.length) {
            return "none";
        }
        String name = NAMES.getOrDefault(trace[base], Long.toHexString(trace[base]));
        return name + " placed=" + (trace[base + 1] == 1L) + " draw=" + Long.toHexString(trace[base + 2]);
    }

    private static long nameHash(String name) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (b & 0xffL)) * 0x100000001b3L;
        }
        return hash;
    }
}
