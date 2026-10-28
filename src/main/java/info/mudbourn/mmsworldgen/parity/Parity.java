package info.mudbourn.mmsworldgen.parity;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

public final class Parity {
    private record Key(String dimension, ParityStage stage, int x, int z) {
    }

    private static volatile boolean active;

    static final AtomicLong CHECKED = new AtomicLong();
    static final AtomicLong MATCHED = new AtomicLong();
    static final AtomicLong MISMATCHED = new AtomicLong();
    static final AtomicLong UNRECORDED = new AtomicLong();
    static final AtomicLong RECORDED = new AtomicLong();

    private static final Map<Key, long[]> LOADED = new ConcurrentHashMap<>();
    private static volatile ParityWriter writer;
    private static volatile boolean recording;

    private Parity() {
    }

    // Whether chunk stages are currently being recorded or checked.
    public static boolean active() {
        return active;
    }

    // Registers server start and stop handling.
    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> start());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> stop());
    }

    static String mode() {
        return MmsWorldgen.config.parity.mode;
    }

    private static synchronized void start() {
        String mode = mode();
        Path dir = FabricLoader.getInstance()
            .getGameDir()
            .resolve("mms_parity")
            .resolve(MmsWorldgen.config.parity.label);
        CHECKED.set(0);
        MATCHED.set(0);
        MISMATCHED.set(0);
        UNRECORDED.set(0);
        RECORDED.set(0);
        LOADED.clear();
        if ("record".equals(mode) && hasFiles(dir)) {
            MmsWorldgen.LOG.error("Parity record refused, {} already has files; change the label or clear it", dir);
        } else if ("record".equals(mode)) {
            writer = new ParityWriter(dir);
            recording = true;
            active = true;
        } else if ("check".equals(mode)) {
            load(dir);
            recording = false;
            active = true;
        }
    }

    private static boolean hasFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.findAny().isPresent();
        } catch (IOException e) {
            MmsWorldgen.LOG.error("Parity could not list {}", dir, e);
            return true;
        }
    }

    private static synchronized void stop() {
        active = false;
        ParityWriter current = writer;
        writer = null;
        if (current != null) {
            current.close();
        }
        LOADED.clear();
    }

    private static void load(Path dir) {
        for (ParityStage stage : ParityStage.values()) {
            String suffix = "." + stage.id + ".bin";
            try (Stream<Path> files = Files.isDirectory(dir) ? Files.list(dir) : Stream.<Path>empty()) {
                files.filter(path -> path.getFileName().toString().endsWith(suffix))
                    .forEach(path -> loadFile(path, stage, suffix));
            } catch (IOException e) {
                MmsWorldgen.LOG.error("Parity could not list {}", dir, e);
            }
        }
        MmsWorldgen.LOG.info("Parity loaded {} recorded chunk-stages from {}", LOADED.size(), dir);
    }

    private static void loadFile(Path path, ParityStage stage, String suffix) {
        String file = path.getFileName().toString();
        String dimension = file.substring(0, file.length() - suffix.length());
        boolean partial = false;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            while (true) {
                partial = false;
                int x = in.readInt();
                partial = true;
                int z = in.readInt();
                long[] hashes = new long[in.readUnsignedShort()];
                for (int i = 0; i < hashes.length; i++) {
                    hashes[i] = in.readLong();
                }
                LOADED.put(new Key(dimension, stage, x, z), hashes);
                partial = false;
            }
        } catch (EOFException e) {
            if (partial) {
                MmsWorldgen.LOG.warn("Parity file {} ends mid-row, last row ignored", file);
            }
            return;
        } catch (IOException e) {
            MmsWorldgen.LOG.error("Parity could not read {}", path, e);
        }
    }

    // Wraps a chunk step future with parity hashing.
    public static CompletableFuture<ChunkAccess> wrap(
        CompletableFuture<ChunkAccess> future,
        ServerLevel level,
        ParityStage stage
    ) {
        if (!active) {
            return future;
        }
        String name = ParityWriter.dimensionName(level.dimension().identifier().toString());
        return future.thenApply(chunk -> {
            try {
                observe(name, stage, chunk);
            } catch (Throwable e) {
                MmsWorldgen.LOG.error("Parity failed dimension={} chunk={}", name, chunk.getPos(), e);
            }
            return chunk;
        });
    }

    private static void observe(String dimension, ParityStage stage, ChunkAccess chunk) {
        if (!active) {
            return;
        }
        int x = chunk.getPos().x;
        int z = chunk.getPos().z;
        long[] hashes = ParityHash.sections(chunk);
        if (recording) {
            ParityWriter current = writer;
            if (current != null) {
                current.submit(dimension, stage, x, z, hashes);
            }
            return;
        }
        CHECKED.incrementAndGet();
        long[] expected = LOADED.get(new Key(dimension, stage, x, z));
        if (expected == null) {
            UNRECORDED.incrementAndGet();
            return;
        }
        boolean same = true;
        for (int i = 0; i < Math.max(expected.length, hashes.length); i++) {
            if (i >= expected.length || i >= hashes.length || expected[i] != hashes[i]) {
                same = false;
                MmsWorldgen.LOG.warn(
                    "Parity mismatch dimension={} chunk=({}, {}) stage={} section={}",
                    dimension,
                    x,
                    z,
                    stage.id,
                    i
                );
            }
        }
        if (same) {
            MATCHED.incrementAndGet();
        } else {
            MISMATCHED.incrementAndGet();
        }
    }
}
