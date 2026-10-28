package info.mudbourn.mmsworldgen.rust;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import info.mudbourn.mmsworldgen.router.RouterDump;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;

public final class RustCompare {

    private static final int POINTS = 2048;
    private static final int MAX_REPORTED = 5;
    private static final Map<String, Long> HANDLES = new LinkedHashMap<>();
    private static final Map<String, String> STATUS = new LinkedHashMap<>();

    private RustCompare() {
    }

    // Registers the server start and stop hooks for the density tree comparison.
    static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(RustCompare::run);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> release());
    }

    // Returns the last status line of each compared dimension.
    public static Map<String, String> status() {
        synchronized (STATUS) {
            return new LinkedHashMap<>(STATUS);
        }
    }

    // Translates every noise dimension and compares Rust against Java at sampled points.
    public static void run(MinecraftServer server) {
        release();
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator) {
                compare(level, generator);
            }
        }
    }

    private static void release() {
        RustShadow.release();
        synchronized (STATUS) {
            HANDLES.values().forEach(RustNative::freeTree);
            HANDLES.clear();
            STATUS.clear();
        }
    }

    private static void record(String dimension, String line, boolean warn) {
        synchronized (STATUS) {
            STATUS.put(dimension, line);
        }
        if (warn) {
            MmsWorldgen.LOG.warn("{}", line);
        } else {
            MmsWorldgen.LOG.info("{}", line);
        }
    }

    private static void compare(ServerLevel level, NoiseBasedChunkGenerator generator) {
        String dimension = level.dimension().identifier().toString();
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        DensityTranslator.Translation translation;
        try {
            translation = DensityTranslator.translate(settings.noiseRouter());
        } catch (DensityTranslator.UnsupportedException e) {
            record(
                dimension,
                "Rust density tree " + dimension + " unsupported: " + e.getMessage() + ", using Java",
                false
            );
            return;
        }

        long handle;
        try {
            handle = RustNative.createTree(level.getSeed(), settings.useLegacyRandomSource(), translation);
        } catch (RuntimeException e) {
            record(dimension, "Rust density tree " + dimension + " failed to build: " + e.getMessage(), true);
            return;
        }
        synchronized (STATUS) {
            HANDLES.put(dimension, handle);
        }

        NoiseRouter router = level.getChunkSource().randomState().router();
        int[] points = points(level.getSeed(), level.getMinY(), level.getMaxY());
        List<String> fields = new ArrayList<>();
        List<String> mismatches = new ArrayList<>();
        int matched = 0;
        int total = 0;
        for (int field = 0; field < RouterDump.FIELDS.size(); field++) {
            RouterDump.Field entry = RouterDump.FIELDS.get(field);
            DensityFunction function = entry.getter().apply(router);
            double[] rust = RustNative.evalTree(handle, field, points);
            int fieldMatched = 0;
            for (int i = 0; i < POINTS; i++) {
                int x = points[i * 3];
                int y = points[i * 3 + 1];
                int z = points[i * 3 + 2];
                double java = function.compute(new DensityFunction.SinglePointContext(x, y, z));
                boolean same = rust != null
                    && Double.doubleToRawLongBits(java) == Double.doubleToRawLongBits(rust[i]);
                if (same) {
                    fieldMatched++;
                } else if (mismatches.size() < MAX_REPORTED) {
                    String found = rust == null ? "null" : Double.toString(rust[i]);
                    mismatches.add(
                        entry.name() + " at " + x + "," + y + "," + z + " java=" + java + " rust=" + found
                    );
                }
            }
            matched += fieldMatched;
            total += POINTS;
            fields.add(entry.name() + " " + fieldMatched + "/" + POINTS);
        }

        String summary = "Rust density tree " + dimension + " matched " + matched + "/" + total
            + " (" + String.join(", ", fields) + ")";
        record(dimension, summary, matched != total);
        if (matched == total) {
            RustShadow.prepare(dimension, level, settings, handle);
        }
        mismatches.forEach(line -> MmsWorldgen.LOG.warn("Rust density mismatch {}: {}", dimension, line));
    }

    private static int[] points(long seed, int minY, int maxY) {
        Random random = new Random(seed ^ 0x5DEECE66DL);
        int height = maxY - minY + 1;
        int[] points = new int[POINTS * 3];
        for (int i = 0; i < POINTS; i++) {
            int range = switch (i % 4) {
                case 0 -> 64;
                case 1 -> 2048;
                case 2 -> 1_000_000;
                default -> 29_999_000;
            };
            points[i * 3] = random.nextInt(range * 2 + 1) - range;
            points[i * 3 + 1] = minY + random.nextInt(height);
            points[i * 3 + 2] = random.nextInt(range * 2 + 1) - range;
        }
        return points;
    }
}
