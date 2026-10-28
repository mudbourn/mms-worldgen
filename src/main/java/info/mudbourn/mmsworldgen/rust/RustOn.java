package info.mudbourn.mmsworldgen.rust;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;

public final class RustOn {

    // A supported dimension: its name and native stage.
    public record Plan(String dimension, RustShadow.Stage stage) {
    }

    private static final int SUMMARY_EVERY = 256;
    private static final int HEIGHTMAP_OFFSET = 512;

    private static final Map<NoiseBasedChunkGenerator, Plan> PLANS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> ACCEPTED_BY_DIMENSION = new ConcurrentHashMap<>();
    private static final AtomicLong ACCEPTED = new AtomicLong();
    private static final AtomicLong FALLBACK_BLEND = new AtomicLong();
    private static final AtomicLong FALLBACK_DIMENSION = new AtomicLong();
    private static final AtomicLong FALLBACK_ERROR = new AtomicLong();
    private static final AtomicLong RUST_NANOS = new AtomicLong();
    private static final AtomicBoolean DISABLED = new AtomicBoolean();

    private RustOn() {
    }

    // Whether the on mode is configured and the library is usable.
    public static boolean active() {
        return "on".equals(MmsWorldgen.config.rust.mode) && RustNative.loaded() && !DISABLED.get();
    }

    static void register(NoiseBasedChunkGenerator generator, String dimension, RustShadow.Stage stage) {
        if ("on".equals(MmsWorldgen.config.rust.mode)) {
            PLANS.put(generator, new Plan(dimension, stage));
        }
    }

    // Frees the plans and reports the final counters.
    static void release() {
        if (total() > 0) {
            MmsWorldgen.LOG.info("Final {}", summary());
        }
        PLANS.clear();
    }

    // Returns the plan for a chunk Rust can fill, counting the reason otherwise.
    public static Plan plan(
        NoiseBasedChunkGenerator generator,
        boolean blended,
        ChunkAccess chunk
    ) {
        Plan plan = PLANS.get(generator);
        if (plan == null) {
            count(FALLBACK_DIMENSION);
            return null;
        }
        if (blended || (chunk instanceof ProtoChunk proto && proto.getBelowZeroRetrogen() != null)) {
            count(FALLBACK_BLEND);
            return null;
        }
        return plan;
    }

    // Fills the chunk from Rust, or runs the Java fill when Rust cannot.
    public static ChunkAccess fill(
        Plan plan,
        ChunkAccess chunk,
        Function<ChunkAccess, NoiseChunk> noiseChunks,
        Supplier<ChunkAccess> javaFill
    ) {
        int[] result;
        RustShadow.Stage stage = plan.stage();
        try {
            if (DISABLED.get()) {
                throw new IllegalStateException("disabled");
            }
            NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(noiseChunks);
            if (!noiseChunk.getBlender().isEmpty()) {
                count(FALLBACK_BLEND);
                return javaFill.get();
            }
            long start = System.nanoTime();
            result = RustNative.fillChunk(
                stage.handle(),
                chunk.getPos().x,
                chunk.getPos().z,
                chunk.getMinY(),
                chunk.getMaxY(),
                RustShadow.beardifier(noiseChunk)
            );
            RUST_NANOS.addAndGet(System.nanoTime() - start);
        } catch (Throwable e) {
            if (DISABLED.compareAndSet(false, true)) {
                MmsWorldgen.LOG.warn("Rust on disabled for this session: {}", e.toString());
            }
            count(FALLBACK_ERROR);
            return javaFill.get();
        }
        write(plan, chunk, result);
        ACCEPTED_BY_DIMENSION.computeIfAbsent(plan.dimension(), ignored -> new AtomicLong()).incrementAndGet();
        count(ACCEPTED);
        return chunk;
    }

    private static void write(Plan plan, ChunkAccess chunk, int[] result) {
        RustShadow.Stage stage = plan.stage();
        BlockState air = Blocks.AIR.defaultBlockState();
        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY();
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int sectionIndex = chunk.getSectionsCount() - 1; sectionIndex >= 0; sectionIndex--) {
            LevelChunkSection section = chunk.getSection(sectionIndex);
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIndex));
            for (int ly = 0; ly < 16; ly++) {
                int y = baseY + ly;
                if (y < minY || y > maxY) {
                    continue;
                }
                int layer = HEIGHTMAP_OFFSET + (y - minY) * 256;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int index = result[layer + z * 16 + x];
                        BlockState state = stage.palette().get(index);
                        if (state == air) {
                            continue;
                        }
                        section.setBlockState(x, ly, z, state, false);
                    }
                }
            }
        }

        for (int mark : RustShadow.marks(result, minY, maxY)) {
            chunk.markPosForPostprocessing(
                pos.set(chunkMinX + (mark & 15), minY + mark / 256, chunkMinZ + ((mark >> 4) & 15))
            );
        }

        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        BlockState solid = Blocks.STONE.defaultBlockState();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int floor = result[x + z * 16];
                int surface = result[256 + x + z * 16];
                if (floor > minY) {
                    oceanFloor.update(x, floor - 1, z, solid);
                }
                if (surface > minY) {
                    worldSurface.update(x, surface - 1, z, solid);
                }
            }
        }
    }

    private static long total() {
        return ACCEPTED.get() + FALLBACK_BLEND.get() + FALLBACK_DIMENSION.get() + FALLBACK_ERROR.get();
    }

    private static void count(AtomicLong counter) {
        counter.incrementAndGet();
        if (total() % SUMMARY_EVERY == 0) {
            MmsWorldgen.LOG.info("{}", summary());
        }
    }

    private static String dimensionSummary() {
        return ACCEPTED_BY_DIMENSION.entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + " accepted=" + e.getValue().get())
            .reduce((a, b) -> a + "; " + b)
            .orElse("");
    }

    // One line with the on-mode counters and the average Rust time.
    public static String summary() {
        long accepted = ACCEPTED.get();
        String average = accepted == 0
            ? "n/a"
            : String.format("%.2fms", RUST_NANOS.get() / 1.0e6 / accepted);
        return "Rust on accepted=" + accepted
            + " fallback=" + (total() - accepted)
            + " (blend=" + FALLBACK_BLEND.get()
            + " dimension=" + FALLBACK_DIMENSION.get()
            + " error=" + FALLBACK_ERROR.get() + ")"
            + " dimensions=[" + dimensionSummary() + "]"
            + " rust_avg=" + average
            + (DISABLED.get() ? " DISABLED" : "");
    }
}
