package info.mudbourn.mmsworldgen.diag;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkSource;

public final class ChunkStall {
    private static final int CALLER_FRAMES = 4;
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Set<Class<?>> PLUMBING = Set.of(
        ServerChunkCache.class,
        ChunkSource.class,
        ServerLevel.class,
        Level.class,
        LevelReader.class,
        LevelAccessor.class,
        CollisionGetter.class,
        BlockGetter.class
    );
    private static final LongArrayList STARTS = new LongArrayList();

    private ChunkStall() {
    }

    // Tells whether an event loop is the chunk cache's main-thread executor.
    public static boolean watches(Object loop) {
        return loop.getClass().getEnclosingClass() == ServerChunkCache.class;
    }

    // Marks the start of a main-thread wait for a chunk.
    public static void begin() {
        STARTS.push(System.nanoTime());
    }

    // Logs the wait that just ended when it ran past the threshold.
    public static void end() {
        if (STARTS.isEmpty()) {
            return;
        }
        long elapsed = System.nanoTime() - STARTS.popLong();
        int thresholdMs = MmsWorldgen.config == null ? 0 : MmsWorldgen.config.diagnostics.chunkStallMs;
        if (thresholdMs <= 0 || elapsed < thresholdMs * 1_000_000L) {
            return;
        }
        MmsWorldgen.LOG.warn("Main thread waited {} ms for a chunk from {}", elapsed / 1_000_000L, callers());
    }

    private static String callers() {
        return WALKER.walk(frames -> frames
            .filter(frame -> !frame.getClassName().startsWith("info.mudbourn.mmsworldgen."))
            .filter(frame -> frame.getDeclaringClass().getEnclosingClass() != ServerChunkCache.class)
            .filter(frame -> !BlockableEventLoop.class.isAssignableFrom(frame.getDeclaringClass()))
            .filter(frame -> PLUMBING.stream().noneMatch(type -> type == frame.getDeclaringClass()))
            .limit(CALLER_FRAMES)
            .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber())
            .collect(Collectors.joining(" < ")));
    }
}
