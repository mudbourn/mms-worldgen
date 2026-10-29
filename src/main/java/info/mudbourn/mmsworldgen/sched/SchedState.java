package info.mudbourn.mmsworldgen.sched;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import info.mudbourn.mmsworldgen.config.WorldgenConfig;
import info.mudbourn.mmsworldgen.mixin.SchedDistanceManagerAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

public final class SchedState {
    private static final int CANDIDATES_PER_PLAYER = 64;
    private static final double FULL_BIAS_SPEED = 8.0;
    private static final double REFERENCE_SPEED = 8.0;
    private static final double MIN_COS = 0.1;
    private static final int MAX_REACH_CHUNKS = 128;
    private static final int PREFETCH_INTERVAL_TICKS = 2;
    private static final int PREFETCH_ISSUES_PER_PASS = 64;
    private static final long PREFETCH_REFRESH_TICKS = 400;
    private static final long STALE_NO_HOLDER_TICKS = 100;
    private static final long STALE_GENERATING_TICKS = 1200;

    private final DistanceManager manager;
    private final Ticket ticket = new Ticket(
        TicketType.PLAYER_LOADING,
        ChunkLevel.byStatus(FullChunkStatus.ENTITY_TICKING)
    );
    private final LongOpenHashSet pending = new LongOpenHashSet();
    private final LongOpenHashSet ticketed = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<Generation> generating = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<UUID> prefetchOwners = new Long2ObjectOpenHashMap<>();
    private final Map<UUID, PlayerBudget> budgets = new LinkedHashMap<>();
    private volatile Thread serverThread;
    private RegionIndex regions;
    private long lastNanos;
    private long tick;
    private int rotation;
    private double generateRate;
    private int concurrentGenerates;

    SchedState(DistanceManager manager) {
        this.manager = manager;
    }

    // Records that a chunk gained or lost demand from the vanilla player tracker.
    synchronized void onDemandChange(long chunk, boolean wantsTicket) {
        if (wantsTicket) {
            if (!ticketed.contains(chunk)) {
                pending.add(chunk);
            }
            return;
        }
        pending.remove(chunk);
        if (ticketed.remove(chunk)) {
            generating.remove(chunk);
            SchedDistanceManagerAccessor access = (SchedDistanceManagerAccessor) manager;
            if (Thread.currentThread() == serverThread) {
                access.mmswg$ticketStorage().removeTicket(chunk, ticket);
            } else {
                access.mmswg$mainThreadExecutor().execute(() -> removeIfUnticketed(access, chunk));
            }
        }
    }

    private synchronized void removeIfUnticketed(SchedDistanceManagerAccessor access, long chunk) {
        if (!ticketed.contains(chunk)) {
            access.mmswg$ticketStorage().removeTicket(chunk, ticket);
        }
    }

    // Issues tickets for pending chunks within each player's budget.
    synchronized void pump(ServerLevel level) {
        WorldgenConfig.Scheduling config = MmsWorldgen.config.scheduling;
        serverThread = Thread.currentThread();
        long now = System.nanoTime();
        double elapsed = lastNanos == 0 ? 0 : (now - lastNanos) / 1.0e9;
        double seconds = Math.min(1.0, elapsed);
        lastNanos = now;
        tick++;

        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (!player.isRemoved()) {
                players.add(player);
            }
        }
        scaleCaps(level.getServer().getPlayerCount(), config);
        updateBudgets(players, config, seconds, elapsed);
        reapGenerating(level);
        if (regions == null) {
            regions = new RegionIndex(
                DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT))
                    .resolve("region")
            );
        }
        if (!pending.isEmpty()) {
            issue(players, rank(players, config), config);
            rotation++;
        }
        if (tick % PREFETCH_INTERVAL_TICKS == 0) {
            for (ServerPlayer player : players) {
                prefetch(player, budgets.get(player.getUUID()), config);
            }
        }
    }

    // Describes each tracked player's budget for the status command.
    synchronized List<String> describe() {
        List<String> lines = new ArrayList<>();
        WorldgenConfig.Scheduling config = MmsWorldgen.config.scheduling;
        for (PlayerBudget budget : budgets.values()) {
            lines.add(
                String.format(
                    "%s: speed %.1f b/s, generate tokens %.1f/%d, load tokens %.1f/%d, queued %d, generating %d/%d,"
                        + " prefetch %d held, %d issued, peak since last status %.1f b/s with %d held",
                    budget.name,
                    budget.speed(),
                    budget.generateTokens,
                    (int) generateRate,
                    budget.loadTokens,
                    config.playerMaxLoadRate,
                    budget.queued,
                    budget.generating,
                    concurrentGenerates,
                    budget.prefetched.size(),
                    budget.prefetchIssued,
                    budget.peakSpeed,
                    budget.peakHeld
                )
            );
            budget.peakSpeed = 0;
            budget.peakHeld = 0;
        }
        return lines;
    }

    private void updateBudgets(List<ServerPlayer> players, WorldgenConfig.Scheduling config, double seconds, double elapsed) {
        Set<UUID> present = new HashSet<>();
        for (ServerPlayer player : players) {
            present.add(player.getUUID());
            PlayerBudget budget = budgets.computeIfAbsent(
                player.getUUID(),
                id -> new PlayerBudget(
                    generateRate,
                    config.playerMaxLoadRate,
                    prefetchCap(config),
                    prefetchLoadCap(config)
                )
            );
            budget.name = player.getGameProfile().name();
            budget.generateTokens = Math.min(
                generateRate,
                budget.generateTokens + generateRate * seconds
            );
            budget.loadTokens = Math.min(
                config.playerMaxLoadRate,
                budget.loadTokens + config.playerMaxLoadRate * seconds
            );
            budget.prefetchTokens = Math.min(
                prefetchCap(config),
                budget.prefetchTokens + generateRate * config.prefetchShare * seconds
            );
            budget.prefetchLoadTokens = Math.min(
                prefetchLoadCap(config),
                budget.prefetchLoadTokens + config.playerMaxLoadRate * config.prefetchShare * seconds
            );
            if (budget.track(player.getX(), player.getZ(), elapsed)) {
                releaseAll(budget);
            }
            budget.queued = 0;
            budget.generating = 0;
            budget.prefetchGenerating = 0;
        }
        budgets.entrySet().removeIf(entry -> {
            if (present.contains(entry.getKey())) {
                return false;
            }
            releaseAll(entry.getValue());
            return true;
        });
        for (Generation generation : generating.values()) {
            PlayerBudget budget = budgets.get(generation.owner);
            if (budget != null) {
                budget.generating++;
                if (generation.prefetch) {
                    budget.prefetchGenerating++;
                }
            }
        }
    }

    private void reapGenerating(ServerLevel level) {
        var chunkMap = level.getChunkSource().chunkMap;
        var iterator = generating.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long age = tick - entry.getValue().startedTick;
            ChunkHolder holder = chunkMap.getUpdatingChunkIfPresent(entry.getLongKey());
            boolean done = holder == null ? age > STALE_NO_HOLDER_TICKS : holder.getFullChunkFuture().isDone();
            if (done || age > STALE_GENERATING_TICKS) {
                PlayerBudget budget = budgets.get(entry.getValue().owner);
                if (budget != null) {
                    budget.generating--;
                    if (entry.getValue().prefetch) {
                        budget.prefetchGenerating--;
                    }
                }
                iterator.remove();
            }
        }
    }

    private List<List<Candidate>> rank(List<ServerPlayer> players, WorldgenConfig.Scheduling config) {
        List<PriorityQueue<Candidate>> heaps = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            heaps.add(new PriorityQueue<>(Comparator.comparingDouble((Candidate c) -> c.key).reversed()));
        }
        if (players.isEmpty()) {
            return List.of();
        }
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            long chunk = iterator.nextLong();
            int owner = nearestPlayer(players, chunk);
            PlayerBudget budget = budgets.get(players.get(owner).getUUID());
            budget.queued++;
            double key = score(players.get(owner), budget, chunk, config.headingBias);
            PriorityQueue<Candidate> heap = heaps.get(owner);
            if (heap.size() < CANDIDATES_PER_PLAYER) {
                heap.add(new Candidate(chunk, key));
            } else if (key < heap.peek().key) {
                heap.poll();
                heap.add(new Candidate(chunk, key));
            }
        }
        List<List<Candidate>> lists = new ArrayList<>();
        for (PriorityQueue<Candidate> heap : heaps) {
            List<Candidate> sorted = new ArrayList<>(heap);
            sorted.sort(Comparator.comparingDouble(c -> c.key));
            lists.add(sorted);
        }
        return lists;
    }

    private void issue(List<ServerPlayer> players, List<List<Candidate>> lists, WorldgenConfig.Scheduling config) {
        if (lists.isEmpty()) {
            return;
        }
        int[] cursor = new int[players.size()];
        boolean progress = true;
        while (progress) {
            progress = false;
            for (int step = 0; step < players.size(); step++) {
                int index = (step + rotation) % players.size();
                PlayerBudget budget = budgets.get(players.get(index).getUUID());
                List<Candidate> list = lists.get(index);
                while (cursor[index] < list.size()) {
                    long chunk = list.get(cursor[index]++).chunk;
                    if (tryIssue(chunk, players.get(index).getUUID(), budget, config)) {
                        progress = true;
                        break;
                    }
                }
            }
        }
    }

    private boolean tryIssue(long chunk, UUID owner, PlayerBudget budget, WorldgenConfig.Scheduling config) {
        Generation running = generating.get(chunk);
        boolean onDisk = regions.contains(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
        if (running != null && running.prefetch) {
            generating.put(chunk, new Generation(running.owner, running.startedTick, false));
            PlayerBudget starter = budgets.get(running.owner);
            if (starter != null) {
                starter.prefetchGenerating--;
            }
        } else if (onDisk) {
            if (budget.loadTokens < 1) {
                return false;
            }
            budget.loadTokens -= 1;
        } else {
            if (budget.generateTokens < 1 || budget.generating >= concurrentGenerates) {
                return false;
            }
            budget.generateTokens -= 1;
            budget.generating++;
            generating.put(chunk, new Generation(owner, tick, false));
        }
        TicketStorage storage = ((SchedDistanceManagerAccessor) manager).mmswg$ticketStorage();
        storage.addTicket(chunk, ticket);
        pending.remove(chunk);
        ticketed.add(chunk);
        budget.queued--;
        return true;
    }

    private static int nearestPlayer(List<ServerPlayer> players, long chunk) {
        int cx = ChunkPos.getX(chunk);
        int cz = ChunkPos.getZ(chunk);
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < players.size(); i++) {
            ChunkPos at = players.get(i).chunkPosition();
            int distance = Math.max(Math.abs(at.x - cx), Math.abs(at.z - cz));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    // Scores a chunk by estimated seconds until the player reaches it, lower first.
    private static double score(ServerPlayer player, PlayerBudget budget, long chunk, boolean headingBias) {
        double dx = ChunkPos.getX(chunk) * 16 + 8 - player.getX();
        double dz = ChunkPos.getZ(chunk) * 16 + 8 - player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        double speed = budget.speed();
        double penalty = 1;
        if (headingBias && distance > 0 && budget.moving()) {
            double cos = (dx * budget.velocityX + dz * budget.velocityZ) / (distance * speed);
            double weight = Math.min(1.0, speed / FULL_BIAS_SPEED);
            penalty = 1 + weight * (1 / Math.max(cos, MIN_COS) - 1);
        }
        return distance * penalty / Math.max(speed, REFERENCE_SPEED);
    }

    // Sets this tick's generate caps from the online player count.
    private void scaleCaps(int online, WorldgenConfig.Scheduling config) {
        if (online > config.capFullPlayers) {
            double share = (double) config.capFullPlayers / online;
            generateRate = config.playerMaxGenerateRate * share;
            concurrentGenerates = Math.max(
                config.minConcurrentGenerates,
                (int) (config.playerMaxConcurrentGenerates * share)
            );
            return;
        }
        double t = Math.max(0, online - config.capStartPlayers)
            / (double) Math.max(1, config.capFullPlayers - config.capStartPlayers);
        generateRate = lerp(config.uncappedGenerateRate, config.playerMaxGenerateRate, t);
        concurrentGenerates = Math.max(
            config.minConcurrentGenerates,
            (int) Math.round(lerp(config.uncappedConcurrentGenerates, config.playerMaxConcurrentGenerates, t))
        );
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private double prefetchCap(WorldgenConfig.Scheduling config) {
        return Math.max(1.0, generateRate * config.prefetchShare);
    }

    private static double prefetchLoadCap(WorldgenConfig.Scheduling config) {
        return Math.max(1.0, config.playerMaxLoadRate * config.prefetchShare);
    }

    private void prefetch(ServerPlayer player, PlayerBudget budget, WorldgenConfig.Scheduling config) {
        boolean enabled = config.lookaheadSeconds > 0 && config.maxPrefetchChunks > 0 && config.prefetchShare > 0;
        if (!enabled || !budget.moving()) {
            releaseAll(budget);
            return;
        }
        double speed = budget.speed();
        budget.peakSpeed = Math.max(budget.peakSpeed, speed);
        budget.peakHeld = Math.max(budget.peakHeld, budget.prefetched.size());
        double ux = budget.velocityX / speed;
        double uz = budget.velocityZ / speed;
        double reach = Math.min(config.lookaheadSeconds * speed, MAX_REACH_CHUNKS * 16.0);
        double cosCone = Math.cos(Math.toRadians(config.prefetchConeDegrees));
        int minX = Mth.floor((player.getX() - reach - 8) / 16.0);
        int maxX = Mth.ceil((player.getX() + reach - 8) / 16.0);
        int minZ = Mth.floor((player.getZ() - reach - 8) / 16.0);
        int maxZ = Mth.ceil((player.getZ() + reach - 8) / 16.0);
        if (cosCone > 0.02) {
            double side = reach * Math.sqrt(1 - cosCone * cosCone) / cosCone;
            double endX = player.getX() + ux * reach;
            double endZ = player.getZ() + uz * reach;
            double leftX = endX - uz * side;
            double leftZ = endZ + ux * side;
            double rightX = endX + uz * side;
            double rightZ = endZ - ux * side;
            minX = Mth.ceil((Math.min(player.getX(), Math.min(leftX, rightX)) - 8) / 16.0);
            maxX = Mth.floor((Math.max(player.getX(), Math.max(leftX, rightX)) - 8) / 16.0);
            minZ = Mth.ceil((Math.min(player.getZ(), Math.min(leftZ, rightZ)) - 8) / 16.0);
            maxZ = Mth.floor((Math.max(player.getZ(), Math.max(leftZ, rightZ)) - 8) / 16.0);
        }
        UUID self = player.getUUID();

        PriorityQueue<Candidate> heap = new PriorityQueue<>(
            Comparator.comparingDouble((Candidate c) -> c.key).reversed()
        );
        LongOpenHashSet keep = new LongOpenHashSet();
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                double dx = cx * 16 + 8 - player.getX();
                double dz = cz * 16 + 8 - player.getZ();
                double along = dx * ux + dz * uz;
                if (along <= 0 || along > reach || along < Math.sqrt(dx * dx + dz * dz) * cosCone) {
                    continue;
                }
                long chunk = ChunkPos.asLong(cx, cz);
                UUID holder = prefetchOwners.get(chunk);
                if (ticketed.contains(chunk) || holder != null && !holder.equals(self)) {
                    continue;
                }
                if (pending.contains(chunk)) {
                    keep.add(chunk);
                    continue;
                }
                if (heap.size() < config.maxPrefetchChunks) {
                    heap.add(new Candidate(chunk, along));
                } else if (along < heap.peek().key) {
                    heap.poll();
                    heap.add(new Candidate(chunk, along));
                }
            }
        }

        List<Candidate> wanted = new ArrayList<>(heap);
        wanted.sort(Comparator.comparingDouble(c -> c.key));
        for (Candidate candidate : wanted) {
            keep.add(candidate.chunk);
        }
        var iterator = budget.prefetched.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!keep.contains(entry.getLongKey())) {
                releaseTicket(entry.getLongKey(), entry.getValue());
                iterator.remove();
            } else if (tick - entry.getValue().tick() > PREFETCH_REFRESH_TICKS) {
                ((SchedDistanceManagerAccessor) manager).mmswg$ticketStorage()
                    .addTicket(entry.getLongKey(), entry.getValue().ticket());
                entry.setValue(new PlayerBudget.Prefetch(entry.getValue().ticket(), tick));
            }
        }

        int issued = 0;
        for (Candidate candidate : wanted) {
            if (issued >= PREFETCH_ISSUES_PER_PASS) {
                break;
            }
            if (!budget.prefetched.containsKey(candidate.chunk)
                && tryPrefetch(candidate.chunk, player.getUUID(), budget, config)) {
                issued++;
            }
        }
    }

    private boolean tryPrefetch(long chunk, UUID owner, PlayerBudget budget, WorldgenConfig.Scheduling config) {
        boolean onDisk = regions.contains(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
        int cap = Math.max(1, (int) (concurrentGenerates * config.prefetchShare));
        if (onDisk) {
            if (budget.loadTokens < 1 || budget.prefetchLoadTokens < 1) {
                return false;
            }
        } else if (budget.generateTokens < 1
            || budget.prefetchTokens < 1
            || budget.generating >= concurrentGenerates
            || budget.prefetchGenerating >= cap) {
            return false;
        }
        Ticket prefetchTicket = new Ticket(Sched.prefetchType(), ChunkLevel.byStatus(FullChunkStatus.FULL));
        if (!((SchedDistanceManagerAccessor) manager).mmswg$ticketStorage().addTicket(chunk, prefetchTicket)) {
            return false;
        }
        if (onDisk) {
            budget.loadTokens -= 1;
            budget.prefetchLoadTokens -= 1;
        } else {
            budget.generateTokens -= 1;
            budget.prefetchTokens -= 1;
            budget.generating++;
            budget.prefetchGenerating++;
            generating.put(chunk, new Generation(owner, tick, true));
        }
        prefetchOwners.put(chunk, owner);
        budget.prefetched.put(chunk, new PlayerBudget.Prefetch(prefetchTicket, tick));
        budget.prefetchIssued++;
        return true;
    }

    private void releaseAll(PlayerBudget budget) {
        for (var entry : budget.prefetched.long2ObjectEntrySet()) {
            releaseTicket(entry.getLongKey(), entry.getValue());
        }
        budget.prefetched.clear();
    }

    private void releaseTicket(long chunk, PlayerBudget.Prefetch prefetch) {
        prefetchOwners.remove(chunk);
        ((SchedDistanceManagerAccessor) manager).mmswg$ticketStorage().removeTicket(chunk, prefetch.ticket());
    }

    private record Candidate(long chunk, double key) {
    }

    private record Generation(UUID owner, long startedTick, boolean prefetch) {
    }
}
