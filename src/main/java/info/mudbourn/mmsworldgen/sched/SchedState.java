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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

public final class SchedState {
    private static final int CANDIDATES_PER_PLAYER = 64;
    private static final long BEHIND_PENALTY = 1L << 40;
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
    private final Map<UUID, PlayerBudget> budgets = new LinkedHashMap<>();
    private volatile Thread serverThread;
    private RegionIndex regions;
    private long lastNanos;
    private long tick;
    private int rotation;

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
        double seconds = lastNanos == 0 ? 0 : Math.min(1.0, (now - lastNanos) / 1.0e9);
        lastNanos = now;
        tick++;

        List<ServerPlayer> players = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (!player.isRemoved()) {
                players.add(player);
            }
        }
        updateBudgets(players, config, seconds);
        reapGenerating(level);
        if (pending.isEmpty()) {
            return;
        }

        List<List<Candidate>> lists = rank(players, config);
        if (regions == null) {
            regions = new RegionIndex(
                DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT))
                    .resolve("region")
            );
        }
        issue(players, lists, config);
        rotation++;
    }

    // Describes each tracked player's budget for the status command.
    synchronized List<String> describe() {
        List<String> lines = new ArrayList<>();
        WorldgenConfig.Scheduling config = MmsWorldgen.config.scheduling;
        for (PlayerBudget budget : budgets.values()) {
            lines.add(
                String.format(
                    "%s: generate tokens %.1f/%d, load tokens %.1f/%d, queued %d, generating %d/%d",
                    budget.name,
                    budget.generateTokens,
                    config.playerMaxGenerateRate,
                    budget.loadTokens,
                    config.playerMaxLoadRate,
                    budget.queued,
                    budget.generating,
                    config.playerMaxConcurrentGenerates
                )
            );
        }
        return lines;
    }

    private void updateBudgets(List<ServerPlayer> players, WorldgenConfig.Scheduling config, double seconds) {
        Set<UUID> present = new HashSet<>();
        for (ServerPlayer player : players) {
            present.add(player.getUUID());
            PlayerBudget budget = budgets.computeIfAbsent(
                player.getUUID(),
                id -> new PlayerBudget(config.playerMaxGenerateRate, config.playerMaxLoadRate)
            );
            budget.name = player.getGameProfile().name();
            budget.generateTokens = Math.min(
                config.playerMaxGenerateRate,
                budget.generateTokens + config.playerMaxGenerateRate * seconds
            );
            budget.loadTokens = Math.min(
                config.playerMaxLoadRate,
                budget.loadTokens + config.playerMaxLoadRate * seconds
            );
            budget.track(player.getX(), player.getZ());
            budget.queued = 0;
            budget.generating = 0;
        }
        budgets.keySet().removeIf(id -> !present.contains(id));
        for (Generation generation : generating.values()) {
            PlayerBudget budget = budgets.get(generation.owner);
            if (budget != null) {
                budget.generating++;
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
                }
                iterator.remove();
            }
        }
    }

    private List<List<Candidate>> rank(List<ServerPlayer> players, WorldgenConfig.Scheduling config) {
        List<PriorityQueue<Candidate>> heaps = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            heaps.add(new PriorityQueue<>(Comparator.comparingLong((Candidate c) -> c.key).reversed()));
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
            long key = key(players.get(owner), budget, chunk, config.headingBias);
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
            sorted.sort(Comparator.comparingLong(c -> c.key));
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
        boolean onDisk = regions.contains(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
        if (onDisk) {
            if (budget.loadTokens < 1) {
                return false;
            }
            budget.loadTokens -= 1;
        } else {
            if (budget.generateTokens < 1 || budget.generating >= config.playerMaxConcurrentGenerates) {
                return false;
            }
            budget.generateTokens -= 1;
            budget.generating++;
            generating.put(chunk, new Generation(owner, tick));
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

    private static long key(ServerPlayer player, PlayerBudget budget, long chunk, boolean headingBias) {
        ChunkPos at = player.chunkPosition();
        long dx = ChunkPos.getX(chunk) - at.x;
        long dz = ChunkPos.getZ(chunk) - at.z;
        long key = dx * dx + dz * dz;
        if (headingBias && budget.moving() && dx * budget.velocityX + dz * budget.velocityZ < 0) {
            key += BEHIND_PENALTY;
        }
        return key;
    }

    private record Candidate(long chunk, long key) {
    }

    private record Generation(UUID owner, long startedTick) {
    }
}
