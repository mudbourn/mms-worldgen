package info.mudbourn.mmsworldgen.rust;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public final class RustSurface {

    // A supported dimension: its native surface system and the palette it was translated with.
    record Plan(
        String dimension,
        long handle,
        BlockState defaultBlock,
        List<BlockState> ruleStates,
        Reference2IntOpenHashMap<Biome> biomeIds,
        SurfaceSystem system,
        boolean legacy
    ) {
    }

    // One chunk's native surface result with the input needed to apply or compare it.
    record Run(
        Plan plan,
        ChunkAccess chunk,
        BiomeManager manager,
        int[] maps,
        List<BlockState> palette,
        int[] before,
        int[] result,
        Map<Integer, Integer> marksBefore,
        long startNanos
    ) {
    }

    private static final class Counts {

        private final AtomicLong compared = new AtomicLong();
        private final AtomicLong matched = new AtomicLong();
        private final AtomicLong mismatched = new AtomicLong();
    }

    private static final int SUMMARY_EVERY = 256;
    private static final int MAX_LOGGED_CHUNKS = 5;
    private static final int MAX_PAIRS = 64;
    private static final int MAPS = 512;
    private static final int CHECK_POINTS = 512;
    private static final int GRID_QUARTS = 6;
    private static final int MAX_DUMPED_COLUMNS = 8;

    private static final Map<NoiseBasedChunkGenerator, Plan> PLANS = new ConcurrentHashMap<>();
    private static final Map<String, String> CHECKS = new ConcurrentHashMap<>();
    private static final Map<String, Counts> BY_DIMENSION = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> PAIRS = new ConcurrentHashMap<>();
    private static final ThreadLocal<Run> PENDING = new ThreadLocal<>();
    private static final AtomicLong COMPARED = new AtomicLong();
    private static final AtomicLong MATCHED = new AtomicLong();
    private static final AtomicLong MISMATCHED = new AtomicLong();
    private static final AtomicLong BLOCK_MISMATCHES = new AtomicLong();
    private static final AtomicLong HEIGHT_MISMATCHES = new AtomicLong();
    private static final AtomicLong MARK_MISMATCHES = new AtomicLong();
    private static final AtomicLong ACCEPTED = new AtomicLong();
    private static final AtomicLong FALLBACK_BLEND = new AtomicLong();
    private static final AtomicLong FALLBACK_DIMENSION = new AtomicLong();
    private static final AtomicLong FALLBACK_BIOME = new AtomicLong();
    private static final AtomicLong FALLBACK_ERROR = new AtomicLong();
    private static final AtomicLong RUST_NANOS = new AtomicLong();
    private static final AtomicLong JAVA_NANOS = new AtomicLong();
    private static final AtomicLong BUILDS = new AtomicLong();
    private static final AtomicInteger LOGGED_CHUNKS = new AtomicInteger();
    private static final AtomicBoolean DISABLED = new AtomicBoolean();

    private RustSurface() {
    }

    private static boolean shadow() {
        return "shadow".equals(MmsWorldgen.config.rust.surfaceMode);
    }

    private static boolean on() {
        return "on".equals(MmsWorldgen.config.rust.surfaceMode);
    }

    // Translates a dimension's surface rule and registers it when the startup checks agree with Java.
    static void prepare(
        String dimension,
        ServerLevel level,
        NoiseBasedChunkGenerator generator,
        NoiseGeneratorSettings settings,
        long treeHandle
    ) {
        if (!shadow() && !on()) {
            return;
        }
        Plan plan = create(
            dimension,
            level.getSeed(),
            settings,
            level.getChunkSource().randomState().surfaceSystem(),
            level.registryAccess().lookupOrThrow(Registries.BIOME),
            level.registryAccess().lookupOrThrow(Registries.NOISE),
            new WorldGenerationContext(generator, level),
            treeHandle
        );
        if (plan != null) {
            PLANS.put(generator, plan);
        }
    }

    // Builds a plan and runs the startup checks, or returns null and records why not.
    static Plan create(
        String dimension,
        long seed,
        NoiseGeneratorSettings settings,
        SurfaceSystem system,
        Registry<Biome> biomes,
        Registry<NormalNoise.NoiseParameters> noises,
        WorldGenerationContext context,
        long treeHandle
    ) {
        SurfaceTranslator.Translation translation;
        try {
            translation = SurfaceTranslator.translate(settings, system, biomes, noises, context);
        } catch (SurfaceTranslator.UnsupportedException e) {
            CHECKS.put(dimension, "Rust surface " + dimension + " unsupported: " + e.getMessage() + ", using Java");
            MmsWorldgen.LOG.info("{}", CHECKS.get(dimension));
            return null;
        } catch (RuntimeException | LinkageError e) {
            CHECKS.put(dimension, "Rust surface " + dimension + " could not be translated: " + e);
            MmsWorldgen.LOG.warn("{}", CHECKS.get(dimension));
            return null;
        }

        long handle;
        try {
            handle = RustNative.createSurface(
                treeHandle,
                seed,
                settings.useLegacyRandomSource(),
                settings.seaLevel(),
                translation
            );
        } catch (RuntimeException | LinkageError e) {
            CHECKS.put(dimension, "Rust surface " + dimension + " failed to build: " + e.getMessage());
            MmsWorldgen.LOG.warn("{}", CHECKS.get(dimension));
            return null;
        }

        Reference2IntOpenHashMap<Biome> biomeIds = new Reference2IntOpenHashMap<>();
        biomeIds.defaultReturnValue(-1);
        for (int id = 0; id < biomes.size(); id++) {
            Biome biome = biomes.byId(id);
            if (biome != null) {
                biomeIds.put(biome, id);
            }
        }
        Plan plan = new Plan(
            dimension,
            handle,
            settings.defaultBlock(),
            translation.states(),
            biomeIds,
            system,
            settings.useLegacyRandomSource()
        );
        String check;
        try {
            check = check(
                seed,
                context.getMinGenY(),
                context.getMinGenY() + context.getGenDepth() - 1,
                plan,
                biomes,
                settings
            );
        } catch (RuntimeException | LinkageError e) {
            RustNative.freeSurface(handle);
            CHECKS.put(dimension, "Rust surface " + dimension + " check failed: " + e);
            MmsWorldgen.LOG.warn("{}", CHECKS.get(dimension));
            return null;
        }
        CHECKS.put(dimension, check);
        if (check.contains("mismatch")) {
            MmsWorldgen.LOG.warn("{}", check);
            RustNative.freeSurface(handle);
            return null;
        }
        MmsWorldgen.LOG.info("{}", check);
        return plan;
    }

    // Frees the plans and reports the final counters.
    static void release() {
        if (COMPARED.get() > 0 || ACCEPTED.get() > 0) {
            MmsWorldgen.LOG.info("Final {}", summary());
        }
        PLANS.values().forEach(plan -> RustNative.freeSurface(plan.handle()));
        PLANS.clear();
        CHECKS.clear();
    }

    // Returns the startup check line of each dimension.
    public static Map<String, String> checks() {
        return new HashMap<>(CHECKS);
    }

    private static String check(
        long seed,
        int minY,
        int maxY,
        Plan plan,
        Registry<Biome> biomes,
        NoiseGeneratorSettings settings
    ) {
        Random random = new Random(seed ^ 0x2545F4914F6CDD1DL ^ plan.dimension().hashCode());
        int biomeCount = biomes.size();

        int[] queries = new int[biomeCount * 4 * 64];
        boolean[] expected = new boolean[biomeCount * 64];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int biome = 0; biome < biomeCount; biome++) {
            for (int i = 0; i < 64; i++) {
                int at = (biome * 64 + i);
                int range = i % 2 == 0 ? 512 : 1_000_000;
                int x = random.nextInt(range * 2 + 1) - range;
                int z = random.nextInt(range * 2 + 1) - range;
                int y = i % 4 == 0
                    ? settings.seaLevel() + 17 + random.nextInt(200)
                    : minY + random.nextInt(maxY - minY + 1);
                queries[at * 4] = biome;
                queries[at * 4 + 1] = x;
                queries[at * 4 + 2] = y;
                queries[at * 4 + 3] = z;
                Biome value = biomes.byId(biome);
                expected[at] = value != null && value.coldEnoughToSnow(pos.set(x, y, z), settings.seaLevel());
            }
        }
        int[] cold = RustNative.surfaceCold(plan.handle(), queries);
        int temperatureMatched = 0;
        for (int i = 0; i < expected.length; i++) {
            Biome value = biomes.byId(i / 64);
            if (value == null || (cold != null && (cold[i] != 0) == expected[i])) {
                temperatureMatched++;
            }
        }

        int chunkX = random.nextInt(2001) - 1000;
        int chunkZ = random.nextInt(2001) - 1000;
        long zoomSeed = BiomeManager.obfuscateSeed(seed);
        int quarts = (maxY - minY + 1) / 4;
        int minQuart = minY >> 2;
        int[] grid = new int[GRID_QUARTS * GRID_QUARTS * quarts];
        for (int qy = 0; qy < quarts; qy++) {
            for (int qz = 0; qz < GRID_QUARTS; qz++) {
                for (int qx = 0; qx < GRID_QUARTS; qx++) {
                    grid[(qy * GRID_QUARTS + qz) * GRID_QUARTS + qx] =
                        syntheticBiome(chunkX * 4 - 1 + qx, minQuart + qy, chunkZ * 4 - 1 + qz, biomeCount);
                }
            }
        }
        BiomeManager manager = new BiomeManager(
            (qx, qy, qz) -> {
                int qyClamped = Math.max(minQuart, Math.min(minQuart + quarts - 1, qy));
                return biomes.wrapAsHolder(biomes.byId(syntheticBiome(qx, qyClamped, qz, biomeCount)));
            },
            zoomSeed
        );
        int[] points = new int[CHECK_POINTS * 3];
        for (int i = 0; i < CHECK_POINTS; i++) {
            points[i * 3] = chunkX * 16 + random.nextInt(16);
            points[i * 3 + 1] = minY + random.nextInt(maxY - minY + 2);
            points[i * 3 + 2] = chunkZ * 16 + random.nextInt(16);
        }
        int[] zoomed = RustNative.surfaceBiomes(zoomSeed, chunkX, chunkZ, minY, maxY, grid, points);
        int zoomMatched = 0;
        for (int i = 0; i < CHECK_POINTS && zoomed != null; i++) {
            Biome java = manager.getBiome(pos.set(points[i * 3], points[i * 3 + 1], points[i * 3 + 2])).value();
            if (plan.biomeIds().getInt(java) == zoomed[i]) {
                zoomMatched++;
            }
        }

        boolean failed = temperatureMatched != expected.length || zoomMatched != CHECK_POINTS;
        return "Rust surface " + plan.dimension()
            + " check temperature " + temperatureMatched + "/" + expected.length
            + " zoom " + zoomMatched + "/" + CHECK_POINTS
            + (failed ? " mismatch, using Java" : " matched");
    }

    private static int syntheticBiome(int qx, int qy, int qz, int biomeCount) {
        long h = qx * 0x9E3779B97F4A7C15L + qy * 0xC2B2AE3D27D4EB4FL + qz * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (int) Long.remainderUnsigned(h, biomeCount);
    }

    private static void count(AtomicLong counter) {
        counter.incrementAndGet();
        if ((ACCEPTED.get() + fallbacks()) % SUMMARY_EVERY == 0 && on()) {
            MmsWorldgen.LOG.info("{}", summary());
        }
    }

    private static long fallbacks() {
        return FALLBACK_BLEND.get() + FALLBACK_DIMENSION.get() + FALLBACK_BIOME.get() + FALLBACK_ERROR.get();
    }

    // Runs Rust for a chunk; true when the result was applied and Java's surface step must be skipped.
    public static boolean handle(NoiseBasedChunkGenerator generator, WorldGenRegion region, ChunkAccess chunk) {
        PENDING.remove();
        if ((!shadow() && !on()) || !RustNative.loaded() || DISABLED.get()) {
            return false;
        }
        if (SharedConstants.debugVoidTerrain(chunk.getPos()) || SharedConstants.DEBUG_DISABLE_SURFACE) {
            return false;
        }
        Plan plan = PLANS.get(generator);
        if (plan == null) {
            count(FALLBACK_DIMENSION);
            return false;
        }
        if ((chunk instanceof ProtoChunk proto && proto.getBelowZeroRetrogen() != null)
            || chunk.isUpgrading()
            || !Blender.of(region).isEmpty()) {
            count(FALLBACK_BLEND);
            return false;
        }
        long start = System.nanoTime();
        Run run;
        try {
            run = compute(plan, region.getBiomeManager(), chunk);
        } catch (Throwable e) {
            if (DISABLED.compareAndSet(false, true)) {
                MmsWorldgen.LOG.warn("Rust surface disabled for this session: {}", e.toString());
            }
            count(FALLBACK_ERROR);
            return false;
        }
        if (run == null) {
            count(FALLBACK_BIOME);
            return false;
        }
        RUST_NANOS.addAndGet(System.nanoTime() - start);
        BUILDS.incrementAndGet();
        if (!on()) {
            PENDING.set(run);
            return false;
        }
        try {
            apply(run);
        } catch (Throwable e) {
            if (DISABLED.compareAndSet(false, true)) {
                MmsWorldgen.LOG.warn("Rust surface disabled for this session: {}", e.toString());
            }
            count(FALLBACK_ERROR);
            return false;
        }
        count(ACCEPTED);
        return true;
    }

    private static int index(List<BlockState> palette, Reference2IntOpenHashMap<BlockState> ids, BlockState state) {
        int id = ids.getInt(state);
        if (id < 0) {
            id = palette.size();
            palette.add(state);
            ids.put(state, id);
        }
        return id;
    }

    static Run compute(Plan plan, BiomeManager manager, ChunkAccess chunk) {
        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY();
        int total = maxY - minY + 1;
        Map<Integer, Integer> marksBefore = marksBefore(chunk, minY);
        Reference2IntOpenHashMap<BlockState> paletteIds = new Reference2IntOpenHashMap<>();
        paletteIds.defaultReturnValue(-1);
        List<BlockState> palette = new ArrayList<>();
        for (BlockState state : plan.ruleStates()) {
            index(palette, paletteIds, state);
        }

        int air = index(palette, paletteIds, Blocks.AIR.defaultBlockState());
        int[] blocks = new int[256 * total];
        for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
            LevelChunkSection section = chunk.getSection(sectionIndex);
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIndex));
            int from = (baseY - minY) * 256;
            if (section.hasOnlyAir()) {
                Arrays.fill(blocks, from, from + 16 * 256, air);
                continue;
            }
            for (int ly = 0; ly < 16; ly++) {
                int layer = from + ly * 256;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        blocks[layer + z * 16 + x] = index(palette, paletteIds, section.getBlockState(x, ly, z));
                    }
                }
            }
        }

        int[] flags = new int[palette.size()];
        for (int i = 0; i < flags.length; i++) {
            BlockState state = palette.get(i);
            flags[i] = (state.isAir() ? 1 : 0)
                | (!state.getFluidState().isEmpty() ? 2 : 0)
                | (state.blocksMotion() ? 4 : 0)
                | (state.is(plan.defaultBlock().getBlock()) ? 8 : 0)
                | (state.is(Blocks.WATER) ? 16 : 0);
        }

        int[] maps = new int[MAPS];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                maps[x + z * 16] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) + 1;
                maps[256 + x + z * 16] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1;
            }
        }

        int quarts = total / 4;
        int minQuart = minY >> 2;
        int firstX = chunk.getPos().x * 4 - 1;
        int firstZ = chunk.getPos().z * 4 - 1;
        int[] grid = new int[GRID_QUARTS * GRID_QUARTS * quarts];
        for (int qy = 0; qy < quarts; qy++) {
            for (int qz = 0; qz < GRID_QUARTS; qz++) {
                for (int qx = 0; qx < GRID_QUARTS; qx++) {
                    Holder<Biome> holder = manager.getNoiseBiomeAtQuart(firstX + qx, minQuart + qy, firstZ + qz);
                    int id = plan.biomeIds().getInt(holder.value());
                    if (id < 0) {
                        return null;
                    }
                    grid[(qy * GRID_QUARTS + qz) * GRID_QUARTS + qx] = id;
                }
            }
        }

        int[] result = RustNative.buildSurface(
            plan.handle(),
            chunk.getPos().x,
            chunk.getPos().z,
            minY,
            maxY,
            manager.biomeZoomSeed,
            maps,
            blocks,
            flags,
            grid
        );
        return new Run(
            plan,
            chunk,
            manager,
            maps,
            palette,
            blocks,
            result,
            marksBefore,
            System.nanoTime()
        );
    }

    // Writes Rust's changed blocks and fluid marks into the chunk the way BlockColumn does.
    static void apply(Run run) {
        ChunkAccess chunk = run.chunk();
        int[] result = run.result();
        int minY = chunk.getMinY();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int cells = 256 * (chunk.getMaxY() - minY + 1);
        int changes = result[MAPS];
        int markAt = MAPS + 1 + changes * 2;
        if (changes < 0
            || result.length <= markAt
            || result[markAt] < 0
            || result.length != markAt + 1 + result[markAt]) {
            throw new IllegalStateException("Rust surface result has an inconsistent length");
        }
        for (int i = 0; i < changes; i++) {
            int at = result[MAPS + 1 + i * 2];
            int index = result[MAPS + 2 + i * 2];
            if (at < 0 || at >= cells || index < 0 || index >= run.palette().size()) {
                throw new IllegalStateException("Rust surface result has an out of range change");
            }
        }
        for (int i = 0; i < result[markAt]; i++) {
            int at = result[markAt + 1 + i];
            if (at < 0 || at >= cells) {
                throw new IllegalStateException("Rust surface result has an out of range mark");
            }
        }
        for (int i = 0; i < changes; i++) {
            int at = result[MAPS + 1 + i * 2];
            BlockState state = run.palette().get(result[MAPS + 2 + i * 2]);
            chunk.setBlockState(pos.set(minX + (at & 15), minY + (at >> 8), minZ + ((at >> 4) & 15)), state);
        }
        for (int i = 0; i < result[markAt]; i++) {
            int at = result[markAt + 1 + i];
            chunk.markPosForPostprocessing(pos.set(minX + (at & 15), minY + (at >> 8), minZ + ((at >> 4) & 15)));
        }
    }

    private static Map<Integer, Integer> marksBefore(ChunkAccess chunk, int minY) {
        Map<Integer, Integer> marks = new HashMap<>();
        ShortList[] lists = chunk.getPostProcessing();
        for (int sectionIndex = 0; sectionIndex < lists.length; sectionIndex++) {
            if (lists[sectionIndex] == null) {
                continue;
            }
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIndex));
            for (short packed : lists[sectionIndex]) {
                int at = (baseY + ((packed >> 4) & 15) - minY) * 256 + ((packed >> 8) & 15) * 16 + (packed & 15);
                marks.merge(at, 1, Integer::sum);
            }
        }
        return marks;
    }

    // Compares the pending Rust result with the chunk Java just built.
    public static void finish(ChunkAccess chunk) {
        Run run = PENDING.get();
        PENDING.remove();
        if (run == null || run.chunk() != chunk || DISABLED.get()) {
            return;
        }
        JAVA_NANOS.addAndGet(System.nanoTime() - run.startNanos());
        try {
            compare(run);
        } catch (Throwable e) {
            if (DISABLED.compareAndSet(false, true)) {
                MmsWorldgen.LOG.warn("Rust surface shadow disabled for this session: {}", e.toString());
            }
        }
    }

    static void compare(Run run) {
        ChunkAccess chunk = run.chunk();
        int[] result = run.result();
        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY();
        int[] expected = run.before().clone();
        int changes = result[MAPS];
        for (int i = 0; i < changes; i++) {
            expected[result[MAPS + 1 + i * 2]] = result[MAPS + 2 + i * 2];
        }

        long blockMismatches = 0;
        String firstBlock = null;
        for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
            LevelChunkSection section = chunk.getSection(sectionIndex);
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIndex));
            for (int ly = 0; ly < 16; ly++) {
                int y = baseY + ly;
                if (y < minY || y > maxY) {
                    continue;
                }
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState java = section.getBlockState(x, ly, z);
                        BlockState rust = run.palette().get(expected[(y - minY) * 256 + z * 16 + x]);
                        if (java != rust) {
                            blockMismatches++;
                            if (firstBlock == null) {
                                firstBlock = (chunk.getPos().getMinBlockX() + x)
                                    + "," + y
                                    + "," + (chunk.getPos().getMinBlockZ() + z)
                                    + " java=" + java
                                    + " rust=" + rust;
                            }
                            countPair(java, rust);
                        }
                    }
                }
            }
        }

        Map<Integer, Integer> javaMarks = marksBefore(chunk, minY);
        run.marksBefore().forEach((key, value) -> javaMarks.merge(key, -value, Integer::sum));
        Map<Integer, Integer> rustMarks = new HashMap<>();
        int markAt = MAPS + 1 + changes * 2;
        for (int i = 0; i < result[markAt]; i++) {
            rustMarks.merge(result[markAt + 1 + i], 1, Integer::sum);
        }
        javaMarks.values().removeIf(value -> value == 0);
        long markMismatches = 0;
        String firstMark = null;
        List<Integer> keys = new ArrayList<>(javaMarks.keySet());
        rustMarks.keySet().stream().filter(key -> !javaMarks.containsKey(key)).forEach(keys::add);
        keys.sort(Integer::compare);
        for (int key : keys) {
            int java = javaMarks.getOrDefault(key, 0);
            int rust = rustMarks.getOrDefault(key, 0);
            if (java != rust) {
                markMismatches++;
                if (firstMark == null) {
                    firstMark = (chunk.getPos().getMinBlockX() + (key & 15)) + "," + (minY + key / 256)
                        + "," + (chunk.getPos().getMinBlockZ() + ((key >> 4) & 15))
                        + " java=" + java + " rust=" + rust;
                }
            }
        }

        long heightMismatches = 0;
        String firstHeight = null;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int floorJava = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) + 1;
                int surfaceJava = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1;
                int floorRust = result[x + z * 16];
                int surfaceRust = result[256 + x + z * 16];
                if (floorJava != floorRust || surfaceJava != surfaceRust) {
                    heightMismatches++;
                    if (firstHeight == null) {
                        firstHeight = "column " + x + "," + z
                            + " ocean_floor java=" + floorJava + " rust=" + floorRust
                            + " world_surface java=" + surfaceJava + " rust=" + surfaceRust;
                    }
                }
            }
        }

        String dimension = run.plan().dimension();
        long compared = COMPARED.incrementAndGet();
        Counts counts = BY_DIMENSION.computeIfAbsent(dimension, ignored -> new Counts());
        counts.compared.incrementAndGet();
        if (blockMismatches == 0 && heightMismatches == 0 && markMismatches == 0) {
            MATCHED.incrementAndGet();
            counts.matched.incrementAndGet();
        } else {
            MISMATCHED.incrementAndGet();
            counts.mismatched.incrementAndGet();
            BLOCK_MISMATCHES.addAndGet(blockMismatches);
            HEIGHT_MISMATCHES.addAndGet(heightMismatches);
            MARK_MISMATCHES.addAndGet(markMismatches);
            if (LOGGED_CHUNKS.incrementAndGet() <= MAX_LOGGED_CHUNKS) {
                MmsWorldgen.LOG.warn(
                    "Rust surface mismatch {} chunk {} blocks={} columns={} marks={} first block {} first {} first mark {}",
                    dimension,
                    chunk.getPos(),
                    blockMismatches,
                    heightMismatches,
                    markMismatches,
                    firstBlock,
                    firstHeight,
                    firstMark
                );
                dumpColumns(run, expected);
            }
        }
        if (compared % SUMMARY_EVERY == 0) {
            MmsWorldgen.LOG.info("{}", summary());
        }
    }

    // Logs the eroded-badlands inputs Java sees for each column whose blocks differ.
    private static void dumpColumns(Run run, int[] expected) {
        ChunkAccess chunk = run.chunk();
        Plan plan = run.plan();
        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY();
        int dumped = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16 && dumped < MAX_DUMPED_COLUMNS; z++) {
            for (int x = 0; x < 16 && dumped < MAX_DUMPED_COLUMNS; x++) {
                int lowest = Integer.MAX_VALUE;
                int highest = Integer.MIN_VALUE;
                for (int y = minY; y <= maxY; y++) {
                    BlockState java = chunk.getBlockState(pos.set(x, y, z));
                    if (java != run.palette().get(expected[(y - minY) * 256 + z * 16 + x])) {
                        lowest = Math.min(lowest, y);
                        highest = Math.max(highest, y);
                    }
                }
                if (lowest == Integer.MAX_VALUE) {
                    continue;
                }
                dumped++;
                int m = chunk.getPos().getMinBlockX() + x;
                int n = chunk.getPos().getMinBlockZ() + z;
                int o = run.maps()[256 + x + z * 16];
                Holder<Biome> biome = run.manager().getBiome(pos.set(m, plan.legacy() ? 0 : o, n));
                MmsWorldgen.LOG.warn(
                    "Rust surface column {},{} biome={} top={} pillar={} diff y={}..{} surface java={} rust={}",
                    m,
                    n,
                    biome.unwrapKey().map(key -> key.identifier().toString()).orElse("?"),
                    o,
                    pillar(run, x, z, m, n, o),
                    lowest,
                    highest,
                    chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) + 1,
                    run.result()[256 + x + z * 16]
                );
            }
        }
    }

    // Replays SurfaceSystem.erodedBadlandsExtension's decision for one column on the pre-surface blocks.
    private static String pillar(Run run, int x, int z, int m, int n, int o) {
        SurfaceSystem system = run.plan().system();
        double e = Math.min(
            Math.abs(system.badlandsSurfaceNoise.getValue(m, 0.0, n) * 8.25),
            system.badlandsPillarNoise.getValue(m * 0.2, 0.0, n * 0.2) * 15.0
        );
        if (e <= 0.0) {
            return "none e=" + e;
        }
        double h = Math.abs(system.badlandsPillarRoofNoise.getValue(m * 0.75, 0.0, n * 0.75) * 1.5);
        int roof = Mth.floor(64.0 + Math.min(e * e * 2.5, Math.ceil(h * 50.0) + 24.0));
        if (o > roof) {
            return "below-top roof=" + roof;
        }
        int minY = run.chunk().getMinY();
        for (int y = roof; y >= minY; y--) {
            BlockState state = run.palette().get(run.before()[(y - minY) * 256 + z * 16 + x]);
            if (state.is(run.plan().defaultBlock().getBlock())) {
                return "build roof=" + roof + " ground=" + y;
            }
            if (state.is(Blocks.WATER)) {
                return "water roof=" + roof + " at=" + y;
            }
        }
        return "build roof=" + roof + " ground=none";
    }

    private static void countPair(BlockState java, BlockState rust) {
        String key = java.getBlock() + " -> " + rust.getBlock();
        AtomicLong counter = PAIRS.get(key);
        if (counter == null && PAIRS.size() < MAX_PAIRS) {
            counter = PAIRS.computeIfAbsent(key, ignored -> new AtomicLong());
        }
        if (counter != null) {
            counter.incrementAndGet();
        }
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

    // One line with the surface counters and average times.
    public static String summary() {
        long builds = BUILDS.get();
        String rustAverage = builds == 0
            ? "n/a"
            : String.format("%.2fms", RUST_NANOS.get() / 1.0e6 / builds);
        String fallback = " fallback=" + fallbacks()
            + " (blend=" + FALLBACK_BLEND.get()
            + " dimension=" + FALLBACK_DIMENSION.get()
            + " biome=" + FALLBACK_BIOME.get()
            + " error=" + FALLBACK_ERROR.get() + ")";
        if (on()) {
            return "Rust surface on accepted=" + ACCEPTED.get()
                + fallback
                + " rust_avg=" + rustAverage
                + (DISABLED.get() ? " DISABLED" : "");
        }
        long compared = COMPARED.get();
        String javaAverage = compared == 0
            ? "n/a"
            : String.format("%.2fms", JAVA_NANOS.get() / 1.0e6 / compared);
        String summary = "Rust surface shadow compared=" + compared
            + " matched=" + MATCHED.get()
            + " mismatched=" + MISMATCHED.get()
            + " block_mismatches=" + BLOCK_MISMATCHES.get()
            + " height_mismatches=" + HEIGHT_MISMATCHES.get()
            + " mark_mismatches=" + MARK_MISMATCHES.get()
            + fallback
            + " dimensions=[" + dimensionSummary() + "]"
            + " java_avg=" + javaAverage
            + " rust_avg=" + rustAverage
            + (DISABLED.get() ? " DISABLED" : "");
        if (MISMATCHED.get() == 0) {
            return summary;
        }
        String pairs = PAIRS.entrySet()
            .stream()
            .sorted(Comparator.comparingLong((Map.Entry<String, AtomicLong> e) -> e.getValue().get()).reversed())
            .limit(5)
            .map(e -> e.getKey() + " x" + e.getValue().get())
            .reduce((a, b) -> a + "; " + b)
            .orElse("");
        return summary + " top_pairs=[" + pairs + "]";
    }
}
