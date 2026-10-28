package info.mudbourn.mmsworldgen.rust;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

public final class RustShadow {

    record Stage(long handle, List<BlockState> palette) {
    }

    private static final class Counts {

        private final AtomicLong compared = new AtomicLong();
        private final AtomicLong matched = new AtomicLong();
        private final AtomicLong mismatched = new AtomicLong();
    }

    private static final int SUMMARY_EVERY = 256;
    private static final int MAX_LOGGED_CHUNKS = 5;
    private static final int MAX_PAIRS = 64;
    private static final int HEIGHTMAP_OFFSET = 512;

    private static final Map<String, Stage> STAGES = new ConcurrentHashMap<>();
    private static final Set<String> SKIPPED_DIMENSIONS = ConcurrentHashMap.newKeySet();
    private static final Map<String, AtomicLong> PAIRS = new ConcurrentHashMap<>();
    private static final Map<String, Counts> BY_DIMENSION = new ConcurrentHashMap<>();
    private static final AtomicLong COMPARED = new AtomicLong();
    private static final AtomicLong MATCHED = new AtomicLong();
    private static final AtomicLong MISMATCHED = new AtomicLong();
    private static final AtomicLong BLOCK_MISMATCHES = new AtomicLong();
    private static final AtomicLong COLUMN_MISMATCHES = new AtomicLong();
    private static final AtomicLong MARK_MISMATCHES = new AtomicLong();
    private static final AtomicLong SKIPPED_BLEND = new AtomicLong();
    private static final AtomicLong SKIPPED_DIMENSION = new AtomicLong();
    private static final AtomicLong SKIPPED_NO_NOISE_CHUNK = new AtomicLong();
    private static final AtomicLong JAVA_NANOS = new AtomicLong();
    private static final AtomicLong JAVA_FILLS = new AtomicLong();
    private static final AtomicLong RUST_NANOS = new AtomicLong();
    private static final AtomicInteger LOGGED_CHUNKS = new AtomicInteger();
    private static final AtomicBoolean DISABLED = new AtomicBoolean();

    private RustShadow() {
    }

    // Whether chunks of supported dimensions are currently shadowed.
    public static boolean active() {
        return "shadow".equals(MmsWorldgen.config.rust.mode) && !STAGES.isEmpty() && !DISABLED.get();
    }

    // Builds the native stage of a dimension whose density tree matched Java.
    static void prepare(
        String dimension,
        ServerLevel level,
        NoiseGeneratorSettings settings,
        long treeHandle
    ) {
        String mode = MmsWorldgen.config.rust.mode;
        if (!"shadow".equals(mode) && !"on".equals(mode)) {
            return;
        }
        try {
            List<BlockState> palette = new ArrayList<>();
            NoiseSettings noise = settings.noiseSettings();
            int[] values = {
                noise.minY(),
                noise.height(),
                noise.noiseSizeHorizontal(),
                noise.noiseSizeVertical(),
                settings.seaLevel(),
                settings.isAquifersEnabled() ? 1 : 0,
                settings.oreVeinsEnabled() ? 1 : 0,
                paletteIndex(palette, Blocks.AIR.defaultBlockState()),
                paletteIndex(palette, settings.defaultBlock()),
                paletteIndex(palette, settings.defaultFluid()),
                paletteIndex(palette, Blocks.LAVA.defaultBlockState()),
                paletteIndex(palette, Blocks.COPPER_ORE.defaultBlockState()),
                paletteIndex(palette, Blocks.RAW_COPPER_BLOCK.defaultBlockState()),
                paletteIndex(palette, Blocks.GRANITE.defaultBlockState()),
                paletteIndex(palette, Blocks.DEEPSLATE_IRON_ORE.defaultBlockState()),
                paletteIndex(palette, Blocks.RAW_IRON_BLOCK.defaultBlockState()),
                paletteIndex(palette, Blocks.TUFF.defaultBlockState()),
                FabricLoader.getInstance().isModLoaded("c2me") ? 1 : 0
            };
            paletteIndex(palette, Blocks.WATER.defaultBlockState());
            int[] flags = new int[palette.size()];
            for (int i = 0; i < flags.length; i++) {
                BlockState state = palette.get(i);
                flags[i] = (state.isAir() ? 1 : 0)
                    | (state.blocksMotion() ? 2 : 0)
                    | (state.is(Blocks.LAVA) ? 4 : 0)
                    | (state.is(Blocks.WATER) ? 8 : 0);
            }
            long handle = RustNative.createStage(
                treeHandle,
                level.getSeed(),
                settings.useLegacyRandomSource(),
                values,
                flags
            );
            Stage stage = new Stage(handle, List.copyOf(palette));
            STAGES.put(dimension, stage);
            if (level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator) {
                RustOn.register(generator, dimension, stage);
            }
        } catch (RuntimeException | LinkageError e) {
            MmsWorldgen.LOG.warn("Rust shadow could not prepare {}: {}", dimension, e.toString());
        }
    }

    private static int paletteIndex(List<BlockState> palette, BlockState state) {
        int index = palette.indexOf(state);
        if (index < 0) {
            palette.add(state);
            index = palette.size() - 1;
        }
        return index;
    }

    // Frees every native stage and resets the shadow state for a new server.
    static void release() {
        if (COMPARED.get() > 0) {
            MmsWorldgen.LOG.info("Final {}", summary());
        }
        RustOn.release();
        STAGES.values().forEach(stage -> RustNative.freeStage(stage.handle()));
        STAGES.clear();
        SKIPPED_DIMENSIONS.clear();
    }

    // Wraps a NOISE step future so Rust shadows the finished chunk.
    public static CompletableFuture<ChunkAccess> wrap(
        CompletableFuture<ChunkAccess> future,
        ServerLevel level
    ) {
        String dimension = level.dimension().identifier().toString();
        return future.thenApply(chunk -> {
            observe(dimension, chunk);
            return chunk;
        });
    }

    // Times the Java fill supplier when shadowing is active.
    public static Supplier<ChunkAccess> timed(Supplier<ChunkAccess> fill) {
        if (!active()) {
            return fill;
        }
        return () -> {
            long start = System.nanoTime();
            try {
                return fill.get();
            } finally {
                JAVA_NANOS.addAndGet(System.nanoTime() - start);
                JAVA_FILLS.incrementAndGet();
            }
        };
    }

    private static void observe(String dimension, ChunkAccess chunk) {
        if (DISABLED.get()) {
            return;
        }
        try {
            Stage stage = STAGES.get(dimension);
            if (stage == null) {
                SKIPPED_DIMENSION.incrementAndGet();
                if (SKIPPED_DIMENSIONS.add(dimension)) {
                    MmsWorldgen.LOG.info("Rust shadow skips {}: no matching density tree", dimension);
                }
                return;
            }
            NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(ignored -> null);
            if (noiseChunk == null) {
                SKIPPED_NO_NOISE_CHUNK.incrementAndGet();
                return;
            }
            if (!noiseChunk.getBlender().isEmpty()
                || (chunk instanceof ProtoChunk proto && proto.getBelowZeroRetrogen() != null)) {
                SKIPPED_BLEND.incrementAndGet();
                return;
            }

            int minY = chunk.getMinY();
            int maxY = chunk.getMaxY();
            long start = System.nanoTime();
            int[] result = RustNative.fillChunk(
                stage.handle(),
                chunk.getPos().x,
                chunk.getPos().z,
                minY,
                maxY,
                beardifier(noiseChunk)
            );
            RUST_NANOS.addAndGet(System.nanoTime() - start);
            compare(dimension, chunk, stage, result, minY, maxY);
        } catch (Throwable e) {
            if (DISABLED.compareAndSet(false, true)) {
                MmsWorldgen.LOG.warn("Rust shadow disabled for this session: {}", e.toString());
            }
        }
    }

    // Rust's fluid update marks: a count then (y - minY) * 256 + z * 16 + x after the blocks.
    static int[] marks(int[] result, int minY, int maxY) {
        int at = HEIGHTMAP_OFFSET + (maxY - minY + 1) * 256;
        return Arrays.copyOfRange(result, at + 1, at + 1 + result[at]);
    }

    private static Set<Integer> javaMarks(ChunkAccess chunk, int minY) {
        Set<Integer> marks = new HashSet<>();
        ShortList[] lists = chunk.getPostProcessing();
        for (int sectionIndex = 0; sectionIndex < lists.length; sectionIndex++) {
            if (lists[sectionIndex] == null) {
                continue;
            }
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIndex));
            for (short packed : lists[sectionIndex]) {
                int x = packed & 15;
                int y = baseY + ((packed >> 4) & 15);
                int z = (packed >> 8) & 15;
                marks.add((y - minY) * 256 + z * 16 + x);
            }
        }
        return marks;
    }

    static int[] beardifier(NoiseChunk noiseChunk) {
        DensityFunctions.BeardifierOrMarker function = noiseChunk.beardifier;
        if (!(function instanceof Beardifier beardifier) || beardifier.affectedBox == null) {
            return new int[] {0};
        }
        List<Beardifier.Rigid> pieces = beardifier.pieces;
        List<JigsawJunction> junctions = beardifier.junctions;
        BoundingBox box = beardifier.affectedBox;
        int[] ints = new int[9 + pieces.size() * 8 + junctions.size() * 3];
        ints[0] = 1;
        ints[1] = box.minX();
        ints[2] = box.minY();
        ints[3] = box.minZ();
        ints[4] = box.maxX();
        ints[5] = box.maxY();
        ints[6] = box.maxZ();
        ints[7] = pieces.size();
        ints[8] = junctions.size();
        int at = 9;
        for (Beardifier.Rigid rigid : pieces) {
            BoundingBox piece = rigid.box();
            ints[at++] = piece.minX();
            ints[at++] = piece.minY();
            ints[at++] = piece.minZ();
            ints[at++] = piece.maxX();
            ints[at++] = piece.maxY();
            ints[at++] = piece.maxZ();
            ints[at++] = rigid.terrainAdjustment().ordinal();
            ints[at++] = rigid.groundLevelDelta();
        }
        for (JigsawJunction junction : junctions) {
            ints[at++] = junction.getSourceX();
            ints[at++] = junction.getSourceGroundY();
            ints[at++] = junction.getSourceZ();
        }
        return ints;
    }

    private static void compare(
        String dimension,
        ChunkAccess chunk,
        Stage stage,
        int[] result,
        int minY,
        int maxY
    ) {
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
                        int index = result[HEIGHTMAP_OFFSET + (y - minY) * 256 + z * 16 + x];
                        BlockState rust = stage.palette().get(index);
                        if (java != rust) {
                            blockMismatches++;
                            if (firstBlock == null) {
                                firstBlock = (chunk.getPos().getMinBlockX() + x)
                                    + "," + y
                                    + "," + (chunk.getPos().getMinBlockZ() + z)
                                    + " java=" + java
                                    + " rust=" + index + ":" + rust;
                            }
                            countPair(java, rust);
                        }
                    }
                }
            }
        }

        Set<Integer> javaMarks = javaMarks(chunk, minY);
        Set<Integer> rustMarks = new HashSet<>();
        for (int mark : marks(result, minY, maxY)) {
            rustMarks.add(mark);
        }
        Set<Integer> markDifference = new HashSet<>(javaMarks);
        markDifference.addAll(rustMarks);
        markDifference.removeAll(
            javaMarks.stream().filter(rustMarks::contains).collect(Collectors.toSet())
        );
        long markMismatches = markDifference.size();
        String firstMark = null;
        if (markMismatches > 0) {
            int mark = markDifference.stream().min(Integer::compare).orElse(0);
            firstMark = (chunk.getPos().getMinBlockX() + (mark & 15))
                + "," + (minY + mark / 256)
                + "," + (chunk.getPos().getMinBlockZ() + ((mark >> 4) & 15))
                + " java=" + javaMarks.contains(mark)
                + " rust=" + rustMarks.contains(mark);
        }

        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        long columnMismatches = 0;
        String firstColumn = null;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int floorJava = oceanFloor.getFirstAvailable(x, z);
                int surfaceJava = worldSurface.getFirstAvailable(x, z);
                int floorRust = result[x + z * 16];
                int surfaceRust = result[256 + x + z * 16];
                if (floorJava != floorRust || surfaceJava != surfaceRust) {
                    columnMismatches++;
                    if (firstColumn == null) {
                        firstColumn = "column " + x + "," + z
                            + " ocean_floor java=" + floorJava + " rust=" + floorRust
                            + " world_surface java=" + surfaceJava + " rust=" + surfaceRust;
                    }
                }
            }
        }

        long compared = COMPARED.incrementAndGet();
        Counts counts = BY_DIMENSION.computeIfAbsent(dimension, ignored -> new Counts());
        counts.compared.incrementAndGet();
        if (blockMismatches == 0 && columnMismatches == 0 && markMismatches == 0) {
            MATCHED.incrementAndGet();
            counts.matched.incrementAndGet();
        } else {
            MISMATCHED.incrementAndGet();
            counts.mismatched.incrementAndGet();
            BLOCK_MISMATCHES.addAndGet(blockMismatches);
            COLUMN_MISMATCHES.addAndGet(columnMismatches);
            MARK_MISMATCHES.addAndGet(markMismatches);
            if (LOGGED_CHUNKS.incrementAndGet() <= MAX_LOGGED_CHUNKS) {
                MmsWorldgen.LOG.warn(
                    "Rust shadow mismatch {} chunk {} blocks={} columns={} marks={} first block {} first {} first mark {}",
                    dimension,
                    chunk.getPos(),
                    blockMismatches,
                    columnMismatches,
                    markMismatches,
                    firstBlock,
                    firstColumn,
                    firstMark
                );
            }
        }
        if (compared % SUMMARY_EVERY == 0) {
            MmsWorldgen.LOG.info("{}", summary());
        }
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

    // One line with the shadow counters and average times.
    public static String summary() {
        long compared = COMPARED.get();
        long javaFills = JAVA_FILLS.get();
        String javaAverage = javaFills == 0
            ? "n/a"
            : String.format("%.2fms", JAVA_NANOS.get() / 1.0e6 / javaFills);
        String rustAverage = compared == 0
            ? "n/a"
            : String.format("%.2fms", RUST_NANOS.get() / 1.0e6 / compared);
        String summary = "Rust shadow compared=" + compared
            + " matched=" + MATCHED.get()
            + " mismatched=" + MISMATCHED.get()
            + " block_mismatches=" + BLOCK_MISMATCHES.get()
            + " column_mismatches=" + COLUMN_MISMATCHES.get()
            + " mark_mismatches=" + MARK_MISMATCHES.get()
            + " skipped_blend=" + SKIPPED_BLEND.get()
            + " skipped_dimension=" + SKIPPED_DIMENSION.get()
            + " skipped_no_noise_chunk=" + SKIPPED_NO_NOISE_CHUNK.get()
            + " dimensions=[" + dimensionSummary() + "]"
            + " java_avg=" + javaAverage + " (" + javaFills + " fills)"
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
