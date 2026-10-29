package info.mudbourn.mmsworldgen.prechunk;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import info.mudbourn.mmsworldgen.config.WorldgenConfig;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public final class PreChunk {
    static final long TICKET_TICKS = 80;
    private static final int MAX_PER_TICK = 4;
    private static final long DEDUPE_TICKS = 20;
    private static final double MAX_LOOKAHEAD_TICKS = 200;
    private static final double MIN_SPEED = 0.05;
    private static final double TELEPORT_SPEED = 10;
    private static final int DEDUPE_PRUNE_SIZE = 1024;
    private static final int LEAD_SAMPLES = 256;

    private static final Map<UUID, LastPos> LAST = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> REQUESTED = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> PENDING = new HashMap<>();
    private static final long[] LEADS = new long[LEAD_SAMPLES];
    private static TicketType ticketType;
    private static long tick;
    private static long requests;
    private static long dedupeSkips;
    private static long completions;
    private static long expired;
    private static long alreadyLoaded;
    private static long chunkySkips;
    private static int leadCount;
    private static Method chunkyGet;
    private static Method chunkyTasks;
    private static boolean chunkyProbed;

    private PreChunk() {
    }

    // Registers the ticket type and the per-server-tick dispatcher.
    public static void init() {
        ticketType = Registry.register(
            BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath("mms_worldgen", "prechunk"),
            new TicketType(TICKET_TICKS, TicketType.FLAG_LOADING)
        );
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> LAST.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> reset());
        ServerTickEvents.END_SERVER_TICK.register(PreChunk::tick);
    }

    // Reports whether movement-predictive loading is switched on.
    public static boolean enabled() {
        return MmsWorldgen.config != null && MmsWorldgen.config.prechunk.enabled;
    }

    private static void reset() {
        LAST.clear();
        REQUESTED.clear();
        PENDING.clear();
        requests = 0;
        dedupeSkips = 0;
        completions = 0;
        expired = 0;
        alreadyLoaded = 0;
        chunkySkips = 0;
        leadCount = 0;
    }

    private static void tick(MinecraftServer server) {
        tick++;
        if (!enabled()) {
            return;
        }
        trackCompletions(server);
        if (chunkyRunning()) {
            chunkySkips++;
            return;
        }
        WorldgenConfig.PreChunk config = MmsWorldgen.config.prechunk;
        double targetBlocks = (server.getPlayerList().getViewDistance() + config.margin) * 16.0;
        int issued = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            ResourceKey<Level> dimension = level.dimension();
            double x = player.getX();
            double z = player.getZ();
            LastPos last = LAST.put(player.getUUID(), new LastPos(dimension, x, z));
            if (last == null || last.dimension() != dimension || issued >= MAX_PER_TICK) {
                continue;
            }
            double dx = x - last.x();
            double dz = z - last.z();
            double speed = Math.sqrt(dx * dx + dz * dz);
            if (speed < MIN_SPEED || speed > TELEPORT_SPEED) {
                continue;
            }
            double lookahead = Math.min(targetBlocks / speed, MAX_LOOKAHEAD_TICKS);
            ChunkPos target = new ChunkPos(
                Mth.floor(x + dx * lookahead) >> 4,
                Mth.floor(z + dz * lookahead) >> 4
            );
            if (request(level, target)) {
                issued++;
            }
        }
    }

    private static boolean request(ServerLevel level, ChunkPos pos) {
        long key = pos.toLong();
        Long2LongOpenHashMap requested = REQUESTED.computeIfAbsent(level.dimension(), k -> new Long2LongOpenHashMap());
        if (requested.containsKey(key) && tick - requested.get(key) < DEDUPE_TICKS) {
            dedupeSkips++;
            return false;
        }
        if (requested.size() > DEDUPE_PRUNE_SIZE) {
            requested.long2LongEntrySet().removeIf(entry -> tick - entry.getLongValue() >= DEDUPE_TICKS);
        }
        requested.put(key, tick);
        level.getChunkSource().addTicketWithRadius(ticketType, pos, 0);
        requests++;
        if (level.getChunkSource().getChunkNow(pos.x, pos.z) == null) {
            PENDING.computeIfAbsent(level.dimension(), k -> new Long2LongOpenHashMap()).putIfAbsent(key, tick);
        } else {
            alreadyLoaded++;
        }
        return true;
    }

    private static void trackCompletions(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            Long2LongOpenHashMap pending = PENDING.get(level.dimension());
            if (pending == null || pending.isEmpty()) {
                continue;
            }
            Iterator<Long2LongMap.Entry> it = pending.long2LongEntrySet().iterator();
            while (it.hasNext()) {
                Long2LongMap.Entry entry = it.next();
                long key = entry.getLongKey();
                long age = tick - entry.getLongValue();
                if (level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null) {
                    recordLead(age);
                    it.remove();
                } else if (age > TICKET_TICKS) {
                    expired++;
                    it.remove();
                }
            }
        }
    }

    private static void recordLead(long ticks) {
        completions++;
        LEADS[leadCount % LEAD_SAMPLES] = ticks;
        leadCount++;
    }

    private static boolean chunkyRunning() {
        if (!chunkyProbed) {
            chunkyProbed = true;
            if (FabricLoader.getInstance().isModLoaded("chunky")) {
                try {
                    Class<?> provider = Class.forName("org.popcraft.chunky.ChunkyProvider");
                    chunkyGet = provider.getMethod("get");
                    chunkyTasks = chunkyGet.getReturnType().getMethod("getGenerationTasks");
                } catch (ReflectiveOperationException e) {
                    MmsWorldgen.LOG.warn("Chunky is loaded but its task list is not reachable", e);
                }
            }
        }
        if (chunkyTasks == null) {
            return false;
        }
        try {
            Object tasks = chunkyTasks.invoke(chunkyGet.invoke(null));
            return tasks instanceof Map<?, ?> map && !map.isEmpty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    // Returns status lines for /mmswg prechunk.
    public static List<String> describe(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        int pending = 0;
        for (Long2LongOpenHashMap map : PENDING.values()) {
            pending += map.size();
        }
        lines.add("requests " + requests
            + ", dedupe skips " + dedupeSkips
            + ", completions " + completions
            + ", expired " + expired
            + ", already loaded " + alreadyLoaded
            + ", pending " + pending
            + ", chunky pause ticks " + chunkySkips);
        int samples = Math.min(leadCount, LEAD_SAMPLES);
        if (samples > 0) {
            long[] sorted = Arrays.copyOf(LEADS, samples);
            Arrays.sort(sorted);
            long sum = 0;
            for (long lead : sorted) {
                sum += lead;
            }
            lines.add(String.format(
                "lead ticks over last %d: mean %.1f, p50 %d, p90 %d, max %d",
                samples,
                (double) sum / samples,
                sorted[samples / 2],
                sorted[Math.min(samples - 1, samples * 9 / 10)],
                sorted[samples - 1]
            ));
        }
        lines.add(String.format("mspt %.2f", server.getAverageTickTimeNanos() / 1_000_000.0));
        return lines;
    }

    private record LastPos(ResourceKey<Level> dimension, double x, double z) {
    }
}
