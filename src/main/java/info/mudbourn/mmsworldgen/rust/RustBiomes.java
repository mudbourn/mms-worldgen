package info.mudbourn.mmsworldgen.rust;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.blending.Blender;

public final class RustBiomes {

    // Picks the search tree index of a quart column.
    interface Regions {

        int at(int quartX, int quartY, int quartZ) throws ReflectiveOperationException;
    }

    // A supported dimension: its native sampler, biome values and region lookup.
    public record Plan(
        String dimension,
        long handle,
        BiomeTreeExport.Export export,
        Regions regions,
        int placeholder,
        AtomicBoolean released,
        boolean vanilla,
        BooleanSupplier stale
    ) {
    }

    private static final class Counts {

        private final AtomicLong compared = new AtomicLong();
        private final AtomicLong matched = new AtomicLong();
        private final AtomicLong mismatched = new AtomicLong();
    }

    private static final int SUMMARY_EVERY = 256;
    private static final int MAX_LOGGED = 5;
    private static final int CLIMATE_POINTS = 4096;
    private static final int RANDOM_TARGETS = 4096;
    private static final int BOUNDARY_TARGETS = 1024;

    private static final Map<NoiseBasedChunkGenerator, Plan> PLANS = new ConcurrentHashMap<>();
    private static final Map<String, String> CHECKS = new ConcurrentHashMap<>();
    private static final Map<String, Counts> BY_DIMENSION = new ConcurrentHashMap<>();
    private static final AtomicLong COMPARED = new AtomicLong();
    private static final AtomicLong MATCHED = new AtomicLong();
    private static final AtomicLong MISMATCHED = new AtomicLong();
    private static final AtomicLong QUART_MISMATCHES = new AtomicLong();
    private static final AtomicLong QUART_TIES = new AtomicLong();
    private static final AtomicLong ACCEPTED = new AtomicLong();
    private static final AtomicLong FALLBACK_BLEND = new AtomicLong();
    private static final AtomicLong FALLBACK_DIMENSION = new AtomicLong();
    private static final AtomicLong FALLBACK_ERROR = new AtomicLong();
    private static final AtomicLong RUST_NANOS = new AtomicLong();
    private static final AtomicLong RUST_CHUNKS = new AtomicLong();
    private static final AtomicLong JAVA_NANOS = new AtomicLong();
    private static final AtomicLong JAVA_CHUNKS = new AtomicLong();
    private static final AtomicInteger LOGGED = new AtomicInteger();
    private static final AtomicBoolean DISABLED = new AtomicBoolean();

    private RustBiomes() {
    }

    private static boolean shadow() {
        return "shadow".equals(MmsWorldgen.config.rust.biomesMode);
    }

    private static boolean on() {
        return "on".equals(MmsWorldgen.config.rust.biomesMode);
    }

    // Builds the biome plan of a dimension and registers it when the startup checks agree with Java.
    static void prepare(
        String dimension,
        ServerLevel level,
        NoiseBasedChunkGenerator generator,
        long treeHandle
    ) {
        if (!shadow() && !on()) {
            return;
        }
        String line;
        try {
            line = build(dimension, level, generator, treeHandle);
        } catch (RuntimeException | ReflectiveOperationException | LinkageError e) {
            line = "Rust biomes " + dimension + " could not be prepared: " + e;
            MmsWorldgen.LOG.warn("{}", line);
        }
        CHECKS.put(dimension, line);
    }

    @SuppressWarnings("unchecked")
    private static String build(
        String dimension,
        ServerLevel level,
        NoiseBasedChunkGenerator generator,
        long treeHandle
    ) throws ReflectiveOperationException {
        BiomeSource source = generator.getBiomeSource();
        if (!(source instanceof MultiNoiseBiomeSource multi)) {
            return info("Rust biomes " + dimension + " unsupported: biome source "
                + source.getClass().getName() + ", using Java");
        }
        Climate.ParameterList<Holder<Biome>> list = multi.parameters();
        List<Climate.RTree<Holder<Biome>>> trees = new ArrayList<>();
        Regions regions = (x, y, z) -> 0;
        boolean vanilla = true;
        BooleanSupplier stale = () -> false;
        Class<?> extended = classOrNull("terrablender.worldgen.IExtendedParameterList");
        if (extended != null && extended.isInstance(list)) {
            Method initialized = extended.getMethod("isInitialized");
            if (!(boolean) initialized.invoke(list)) {
                stale = () -> {
                    try {
                        return (boolean) initialized.invoke(list);
                    } catch (ReflectiveOperationException e) {
                        return true;
                    }
                };
                trees.add(list.index);
            } else {
                vanilla = false;
                int count = (int) extended.getMethod("getTreeCount").invoke(list);
                Method tree = extended.getMethod("getTree", int.class);
                for (int i = 0; i < count; i++) {
                    try {
                        trees.add((Climate.RTree<Holder<Biome>>) tree.invoke(list, i));
                    } catch (InvocationTargetException e) {
                        trees.add(null);
                    }
                }
                Method uniqueness = extended.getMethod("getUniqueness", int.class, int.class, int.class);
                regions = (x, y, z) -> (int) uniqueness.invoke(list, x, y, z);
            }
        } else {
            trees.add(list.index);
        }
        if (trees.isEmpty() || trees.get(0) == null) {
            return info("Rust biomes " + dimension + " unsupported: no first search tree, using Java");
        }

        BiomeTreeExport.Export export = BiomeTreeExport.export(trees);
        int placeholder = placeholder(export);
        long handle;
        try {
            handle = RustNative.createBiomes(
                treeHandle,
                export.spans(),
                export.meta(),
                export.children(),
                export.roots(),
                placeholder
            );
        } catch (RuntimeException e) {
            return warn("Rust biomes " + dimension + " failed to build: " + e.getMessage());
        }
        Plan plan = new Plan(
            dimension,
            handle,
            export,
            regions,
            placeholder,
            new AtomicBoolean(),
            vanilla,
            stale
        );
        String check = check(level, plan, trees, list);
        if (check.contains("mismatch")) {
            RustNative.freeBiomes(handle);
            return warn(check);
        }
        PLANS.put(generator, plan);
        return info(check);
    }

    private static String info(String line) {
        MmsWorldgen.LOG.info("{}", line);
        return line;
    }

    private static String warn(String line) {
        MmsWorldgen.LOG.warn("{}", line);
        return line;
    }

    private static Class<?> classOrNull(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static int placeholder(BiomeTreeExport.Export export) throws ReflectiveOperationException {
        Class<?> region = classOrNull("terrablender.api.Region");
        if (region == null) {
            return -1;
        }
        ResourceKey<Biome> key = (ResourceKey<Biome>) region.getField("DEFERRED_PLACEHOLDER").get(null);
        for (int id = 0; id < export.holders().size(); id++) {
            if (export.holders().get(id).is(key)) {
                return id;
            }
        }
        return -1;
    }

    // Frees the plans and reports the final counters.
    static void release() {
        if (COMPARED.get() > 0 || ACCEPTED.get() > 0) {
            MmsWorldgen.LOG.info("Final {}", summary());
        }
        PLANS.values().forEach(plan -> {
            plan.released().set(true);
            RustNative.freeBiomes(plan.handle());
        });
        PLANS.clear();
        CHECKS.clear();
        BY_DIMENSION.clear();
        for (AtomicLong counter : new AtomicLong[] {
            COMPARED,
            MATCHED,
            MISMATCHED,
            QUART_MISMATCHES,
            QUART_TIES,
            ACCEPTED,
            FALLBACK_BLEND,
            FALLBACK_DIMENSION,
            FALLBACK_ERROR,
            RUST_NANOS,
            RUST_CHUNKS,
            JAVA_NANOS,
            JAVA_CHUNKS
        }) {
            counter.set(0);
        }
        LOGGED.set(0);
        DISABLED.set(false);
    }

    // Returns the startup check line of each dimension.
    public static Map<String, String> checks() {
        return new HashMap<>(CHECKS);
    }

    private static String check(
        ServerLevel level,
        Plan plan,
        List<Climate.RTree<Holder<Biome>>> trees,
        Climate.ParameterList<Holder<Biome>> list
    ) {
        Random random = new Random(level.getSeed() ^ 0x6A09E667F3BCC909L ^ plan.dimension().hashCode());
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int minQuart = QuartPos.fromBlock(level.getMinY());
        int quartHeight = (level.getMaxY() - level.getMinY() + 1) >> 2;

        int[] quarts = new int[CLIMATE_POINTS * 3];
        for (int i = 0; i < CLIMATE_POINTS; i++) {
            int range = switch (i % 4) {
                case 0 -> 16;
                case 1 -> 512;
                case 2 -> 250_000;
                default -> 7_000_000;
            };
            quarts[i * 3] = random.nextInt(range * 2 + 1) - range;
            quarts[i * 3 + 1] = minQuart + random.nextInt(quartHeight);
            quarts[i * 3 + 2] = random.nextInt(range * 2 + 1) - range;
        }
        long[] rustClimate = RustNative.biomeClimate(plan.handle(), quarts);
        int climateMatched = 0;
        for (int i = 0; i < CLIMATE_POINTS && rustClimate != null; i++) {
            long[] java = climate(sampler.sample(quarts[i * 3], quarts[i * 3 + 1], quarts[i * 3 + 2]));
            boolean same = true;
            for (int k = 0; k < 6; k++) {
                same &= java[k] == rustClimate[i * 6 + k];
            }
            if (same) {
                climateMatched++;
            }
        }

        int lookupMatched = 0;
        int lookupTotal = 0;
        int ties = 0;
        for (int tree = 0; tree < trees.size(); tree++) {
            if (trees.get(tree) == null) {
                continue;
            }
            long[] targets = targets(random, plan.export().leafSpans()[tree]);
            int[] rust = RustNative.biomeSearch(plan.handle(), tree, targets);
            Climate.DistanceMetric<Holder<Biome>> metric = (node, values) -> node.distance(values);
            for (int i = 0; i < targets.length / 6; i++) {
                lookupTotal++;
                long[] target = Arrays.copyOfRange(targets, i * 6, i * 6 + 6);
                Climate.TargetPoint point = new Climate.TargetPoint(
                    target[0],
                    target[1],
                    target[2],
                    target[3],
                    target[4],
                    target[5]
                );
                Holder<Biome> java = plan.vanilla() ? list.findValue(point) : trees.get(tree).search(point, metric);
                if (rust == null) {
                    continue;
                }
                Holder<Biome> chosen = plan.export().holders().get(rust[i]);
                if (java == chosen) {
                    lookupMatched++;
                } else if (tie(plan, tree, java, chosen, target)) {
                    lookupMatched++;
                    ties++;
                }
            }
        }

        boolean failed = climateMatched != CLIMATE_POINTS || lookupMatched != lookupTotal;
        return "Rust biomes " + plan.dimension()
            + " check climate " + climateMatched + "/" + CLIMATE_POINTS
            + " lookup " + lookupMatched + "/" + lookupTotal
            + (failed ? " mismatch, using Java" : " matched")
            + (ties > 0 ? " (ties " + ties + ")" : "");
    }

    private static long[] climate(Climate.TargetPoint point) {
        return new long[] {
            point.temperature(),
            point.humidity(),
            point.continentalness(),
            point.erosion(),
            point.depth(),
            point.weirdness()
        };
    }

    private static long[] targets(Random random, long[] leafSpans) {
        long[] targets = new long[(RANDOM_TARGETS + BOUNDARY_TARGETS) * 6];
        for (int i = 0; i < RANDOM_TARGETS * 6; i++) {
            targets[i] = random.nextInt(30_001) - 15_000;
        }
        int leaves = leafSpans.length / (BiomeTreeExport.DIMS * 2);
        for (int i = 0; i < BOUNDARY_TARGETS; i++) {
            int base = random.nextInt(leaves) * BiomeTreeExport.DIMS * 2;
            for (int k = 0; k < 6; k++) {
                long edge = leafSpans[base + k * 2 + random.nextInt(2)];
                targets[(RANDOM_TARGETS + i) * 6 + k] = random.nextBoolean() ? edge : edge + random.nextInt(3) - 1;
            }
        }
        return targets;
    }

    // Whether two biome values are equally near the target in the trees that produced them.
    private static boolean tie(Plan plan, int tree, Holder<Biome> java, Holder<Biome> rust, long[] target) {
        Integer javaId = plan.export().ids().get(java);
        Integer rustId = plan.export().ids().get(rust);
        if (javaId == null || rustId == null) {
            return false;
        }
        boolean javaDeferred = tree != 0 && distance(plan, tree, javaId, target) < 0;
        boolean rustDeferred = tree != 0 && distance(plan, tree, rustId, target) < 0;
        if (javaDeferred && rustDeferred) {
            return equidistant(plan, 0, javaId, rustId, target);
        }
        if (javaDeferred) {
            return equidistant(plan, tree, plan.placeholder(), rustId, target);
        }
        if (rustDeferred) {
            return equidistant(plan, tree, javaId, plan.placeholder(), target);
        }
        return equidistant(plan, tree, javaId, rustId, target);
    }

    private static long distance(Plan plan, int tree, int id, long[] target) {
        return id < 0 ? -1 : RustNative.biomeDistance(plan.handle(), tree, id, target);
    }

    private static boolean equidistant(Plan plan, int tree, int first, int second, long[] target) {
        long firstDistance = distance(plan, tree, first, target);
        return firstDistance >= 0 && firstDistance == distance(plan, tree, second, target);
    }

    private static void count(AtomicLong counter) {
        counter.incrementAndGet();
        if ((ACCEPTED.get() + fallbacks()) % SUMMARY_EVERY == 0 && on()) {
            MmsWorldgen.LOG.info("{}", summary());
        }
    }

    private static long fallbacks() {
        return FALLBACK_BLEND.get() + FALLBACK_DIMENSION.get() + FALLBACK_ERROR.get();
    }

    private static Plan plan(NoiseBasedChunkGenerator generator, Blender blender, ChunkAccess chunk) {
        if (!RustNative.loaded() || DISABLED.get()) {
            return null;
        }
        Plan plan = PLANS.get(generator);
        if (plan == null) {
            count(FALLBACK_DIMENSION);
            return null;
        }
        if (plan.stale().getAsBoolean()) {
            PLANS.remove(generator, plan);
            count(FALLBACK_DIMENSION);
            return null;
        }
        if (!blender.isEmpty() || chunk.isUpgrading()) {
            count(FALLBACK_BLEND);
            return null;
        }
        return plan;
    }

    // Returns the plan when on mode will fill this chunk's biomes, counting the reason otherwise.
    public static Plan onPlan(NoiseBasedChunkGenerator generator, Blender blender, ChunkAccess chunk) {
        return on() ? plan(generator, blender, chunk) : null;
    }

    private record Result(int[] ids, int[] regions, int minQuartX, int minQuartY, int minQuartZ) {
    }

    private static Result compute(Plan plan, ChunkAccess chunk) throws ReflectiveOperationException {
        if (plan.released().get()) {
            throw new IllegalStateException("Rust biomes plan was released");
        }
        LevelHeightAccessor height = chunk.getHeightAccessorForGeneration();
        int minQuartY = QuartPos.fromSection(height.getMinSectionY());
        int quartCount = height.getSectionsCount() * 4;
        int minQuartX = chunk.getPos().x * 4;
        int minQuartZ = chunk.getPos().z * 4;
        int[] regions = new int[16];
        for (int lx = 0; lx < 4; lx++) {
            for (int lz = 0; lz < 4; lz++) {
                regions[lx * 4 + lz] = plan.regions().at(minQuartX + lx, 0, minQuartZ + lz);
            }
        }
        int[] ids = RustNative.fillBiomes(
            plan.handle(),
            chunk.getPos().x,
            chunk.getPos().z,
            minQuartY,
            quartCount,
            regions
        );
        if (ids == null || ids.length != quartCount * 16) {
            throw new IllegalStateException("Rust biomes result has an inconsistent length");
        }
        for (int id : ids) {
            if (id < 0 || id >= plan.export().holders().size()) {
                throw new IllegalStateException("Rust biomes result has an unknown biome id");
            }
        }
        return new Result(ids, regions, minQuartX, minQuartY, minQuartZ);
    }

    private static int cell(Result result, int qx, int qy, int qz) {
        return (((qy - result.minQuartY()) * 4 + (qx - result.minQuartX())) * 4) + (qz - result.minQuartZ());
    }

    // Fills a chunk's biomes from Rust in on mode; false means Java must do it.
    public static boolean fill(Plan plan, ChunkAccess chunk) {
        long start = System.nanoTime();
        try {
            Result result = compute(plan, chunk);
            List<Holder<Biome>> holders = plan.export().holders();
            chunk.fillBiomesFromNoise(
                (x, y, z, sampler) -> holders.get(result.ids()[cell(result, x, y, z)]),
                null
            );
        } catch (Throwable e) {
            if (plan.released().get()) {
                return false;
            }
            if (DISABLED.compareAndSet(false, true)) {
                MmsWorldgen.LOG.warn("Rust biomes disabled for this session: {}", e.toString());
            }
            count(FALLBACK_ERROR);
            return false;
        }
        RUST_NANOS.addAndGet(System.nanoTime() - start);
        RUST_CHUNKS.incrementAndGet();
        count(ACCEPTED);
        return true;
    }

    // Times the Java biome supplier when shadowing is active.
    public static Supplier<ChunkAccess> timed(Supplier<ChunkAccess> biomes) {
        if (!shadow()) {
            return biomes;
        }
        return () -> {
            long start = System.nanoTime();
            try {
                return biomes.get();
            } finally {
                JAVA_NANOS.addAndGet(System.nanoTime() - start);
                JAVA_CHUNKS.incrementAndGet();
            }
        };
    }

    // Wraps a biome step future so Rust shadows the finished chunk in shadow mode.
    public static CompletableFuture<ChunkAccess> shadow(
        NoiseBasedChunkGenerator generator,
        Climate.Sampler sampler,
        Blender blender,
        ChunkAccess chunk,
        CompletableFuture<ChunkAccess> future
    ) {
        if (!shadow()) {
            return future;
        }
        Plan plan = plan(generator, blender, chunk);
        if (plan == null) {
            return future;
        }
        return future.thenApply(done -> {
            observe(plan, sampler, done);
            return done;
        });
    }

    private static void observe(Plan plan, Climate.Sampler sampler, ChunkAccess chunk) {
        if (DISABLED.get()) {
            return;
        }
        try {
            long start = System.nanoTime();
            Result result = compute(plan, chunk);
            RUST_NANOS.addAndGet(System.nanoTime() - start);
            RUST_CHUNKS.incrementAndGet();
            compare(plan, sampler, chunk, result);
        } catch (Throwable e) {
            if (plan.released().get()) {
                return;
            }
            if (DISABLED.compareAndSet(false, true)) {
                MmsWorldgen.LOG.warn("Rust biomes shadow disabled for this session: {}", e.toString());
            }
            count(FALLBACK_ERROR);
        }
    }

    private static void compare(Plan plan, Climate.Sampler sampler, ChunkAccess chunk, Result result) {
        LevelHeightAccessor height = chunk.getHeightAccessorForGeneration();
        long mismatches = 0;
        long ties = 0;
        for (int sectionY = height.getMinSectionY(); sectionY <= height.getMaxSectionY(); sectionY++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            int baseQuartY = QuartPos.fromSection(sectionY);
            for (int lx = 0; lx < 4; lx++) {
                for (int ly = 0; ly < 4; ly++) {
                    for (int lz = 0; lz < 4; lz++) {
                        int qx = result.minQuartX() + lx;
                        int qy = baseQuartY + ly;
                        int qz = result.minQuartZ() + lz;
                        Holder<Biome> java = section.getNoiseBiome(lx, ly, lz);
                        Holder<Biome> rust = plan.export().holders().get(result.ids()[cell(result, qx, qy, qz)]);
                        if (java == rust || Objects.equals(java.unwrapKey(), rust.unwrapKey())) {
                            continue;
                        }
                        long[] target = climate(sampler.sample(qx, qy, qz));
                        int region = result.regions()[lx * 4 + lz];
                        if (tie(plan, region, java, rust, target)) {
                            ties++;
                            continue;
                        }
                        mismatches++;
                        logMismatch(plan, chunk, qx, qy, qz, region, java, rust, target);
                    }
                }
            }
        }

        long compared = COMPARED.incrementAndGet();
        Counts counts = BY_DIMENSION.computeIfAbsent(plan.dimension(), ignored -> new Counts());
        counts.compared.incrementAndGet();
        QUART_TIES.addAndGet(ties);
        if (mismatches == 0) {
            MATCHED.incrementAndGet();
            counts.matched.incrementAndGet();
        } else {
            MISMATCHED.incrementAndGet();
            counts.mismatched.incrementAndGet();
            QUART_MISMATCHES.addAndGet(mismatches);
        }
        if (compared % SUMMARY_EVERY == 0) {
            MmsWorldgen.LOG.info("{}", summary());
        }
    }

    private static void logMismatch(
        Plan plan,
        ChunkAccess chunk,
        int qx,
        int qy,
        int qz,
        int region,
        Holder<Biome> java,
        Holder<Biome> rust,
        long[] target
    ) {
        if (LOGGED.incrementAndGet() > MAX_LOGGED) {
            return;
        }
        long[] rustClimate = RustNative.biomeClimate(plan.handle(), new int[] {qx, qy, qz});
        MmsWorldgen.LOG.warn(
            "Rust biomes mismatch {} chunk {} quart {},{},{} region {} java={} rust={} java_climate={} rust_climate={}",
            plan.dimension(),
            chunk.getPos(),
            qx,
            qy,
            qz,
            region,
            key(java),
            key(rust),
            Arrays.toString(target),
            Arrays.toString(rustClimate)
        );
    }

    private static String key(Holder<Biome> holder) {
        return holder.unwrapKey().map(k -> k.identifier().toString()).orElse("inline");
    }

    private static String dimensionSummary() {
        return BY_DIMENSION.entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey()
                + " compared=" + e.getValue().compared.get()
                + " matched=" + e.getValue().matched.get()
                + " mismatched=" + e.getValue().mismatched.get())
            .reduce((a, b) -> a + "; " + b)
            .orElse("");
    }

    private static String average(long nanos, long chunks) {
        return chunks == 0 ? "n/a" : String.format("%.2fms", nanos / 1.0e6 / chunks);
    }

    // One line with the biome counters and average times.
    public static String summary() {
        String fallback = " fallback=" + fallbacks()
            + " (blend=" + FALLBACK_BLEND.get()
            + " dimension=" + FALLBACK_DIMENSION.get()
            + " error=" + FALLBACK_ERROR.get() + ")";
        String rustAverage = average(RUST_NANOS.get(), RUST_CHUNKS.get());
        if (on()) {
            return "Rust biomes on accepted=" + ACCEPTED.get()
                + fallback
                + " rust_avg=" + rustAverage
                + (DISABLED.get() ? " DISABLED" : "");
        }
        return "Rust biomes shadow compared=" + COMPARED.get()
            + " matched=" + MATCHED.get()
            + " mismatched=" + MISMATCHED.get()
            + " quart_mismatches=" + QUART_MISMATCHES.get()
            + " quart_ties=" + QUART_TIES.get()
            + fallback
            + " dimensions=[" + dimensionSummary() + "]"
            + " java_avg=" + average(JAVA_NANOS.get(), JAVA_CHUNKS.get())
            + " rust_avg=" + rustAverage
            + (DISABLED.get() ? " DISABLED" : "");
    }
}
