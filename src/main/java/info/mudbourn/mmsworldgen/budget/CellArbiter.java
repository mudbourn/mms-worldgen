package info.mudbourn.mmsworldgen.budget;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToDoubleFunction;

public final class CellArbiter {

    private static final long MAIN_SALT = 0x4D4D5357474D41L;
    private static final long BASE_SALT = 0x4D4D5357474241L;
    private static final int SAMPLE_QUART_Y = 64 >> 2;

    public record Entry(Identifier id, String group, double weight, int separation, StructureSet set) {}

    public record Group(String name, double weight, List<Entry> entries) {}

    public record Settings(int cellSize, double emptyChance, int noveltyRadius, double noveltyFalloff, int retries) {}

    public record Candidate(Entry entry, double novelty, boolean biomeFail, ChunkPos chunk) {}

    public record Result(boolean emptyRoll, Entry owner, ChunkPos start, List<Candidate> candidates) {}

    private final long seed;
    private final Settings settings;
    private final List<Group> groups;
    private final BiomeSource biomeSource;
    private final Climate.Sampler sampler;
    private final LoadingCache<Long, Result> cache;

    public CellArbiter(
        long seed,
        Settings settings,
        List<Group> groups,
        BiomeSource biomeSource,
        Climate.Sampler sampler
    ) {
        this.seed = seed;
        this.settings = settings;
        this.groups = groups;
        this.biomeSource = biomeSource;
        this.sampler = sampler;
        this.cache = CacheBuilder.newBuilder()
            .maximumSize(16384)
            .build(CacheLoader.from(key -> compute(unpackX(key), unpackZ(key))));
    }

    public int cellSize() {
        return settings.cellSize();
    }

    public List<Group> groups() {
        return groups;
    }

    // Returns the cached outcome for a cell, computing it on first use.
    public Result resultAt(int cellX, int cellZ) {
        return cache.getUnchecked(pack(cellX, cellZ));
    }

    private Result compute(int cx, int cz) {
        RandomSource rnd = random(cx, cz, MAIN_SALT);
        if (rnd.nextDouble() < settings.emptyChance()) {
            return new Result(true, null, null, List.of());
        }

        List<Entry> previous = previousPicks(cx, cz);
        ToDoubleFunction<Entry> novelty = e -> noveltyFactor(e, previous);
        Set<Entry> failed = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>();

        for (int attempt = 0; attempt <= settings.retries(); attempt++) {
            Entry entry = pick(rnd, failed, novelty);
            if (entry == null) {
                break;
            }

            ChunkPos chunk = randomChunk(rnd, cx, cz, entry.separation());
            boolean biomeFail = !biomeAllows(entry, chunk);
            candidates.add(new Candidate(entry, novelty.applyAsDouble(entry), biomeFail, chunk));
            if (!biomeFail) {
                return new Result(false, entry, chunk, candidates);
            }
            failed.add(entry);
        }
        return new Result(false, null, null, candidates);
    }

    private List<Entry> previousPicks(int cx, int cz) {
        List<Entry> picks = new ArrayList<>();
        int radius = settings.noveltyRadius();
        for (int dz = -radius; dz <= 0; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dz == 0 && dx >= 0) {
                    break;
                }
                Entry pick = basePick(cx + dx, cz + dz);
                if (pick != null) {
                    picks.add(pick);
                }
            }
        }
        return picks;
    }

    private Entry basePick(int cx, int cz) {
        RandomSource rnd = random(cx, cz, BASE_SALT);
        if (rnd.nextDouble() < settings.emptyChance()) {
            return null;
        }
        return pick(rnd, Set.of(), e -> 1.0);
    }

    private double noveltyFactor(Entry entry, List<Entry> previous) {
        int count = 0;
        for (Entry other : previous) {
            if (other == entry) {
                count++;
            }
        }
        return Math.pow(settings.noveltyFalloff(), count);
    }

    private Entry pick(RandomSource rnd, Set<Entry> excluded, ToDoubleFunction<Entry> factor) {
        List<Group> open = new ArrayList<>();
        for (Group group : groups) {
            if (group.entries().stream().anyMatch(e -> !excluded.contains(e) && e.weight() * factor.applyAsDouble(e) > 0)) {
                open.add(group);
            }
        }

        Group group = weighted(rnd, open, Group::weight);
        if (group == null) {
            return null;
        }

        List<Entry> candidates = group.entries().stream().filter(e -> !excluded.contains(e)).toList();
        return weighted(rnd, candidates, e -> e.weight() * factor.applyAsDouble(e));
    }

    private static <T> T weighted(RandomSource rnd, List<T> items, ToDoubleFunction<T> weight) {
        double total = 0;
        for (T item : items) {
            total += weight.applyAsDouble(item);
        }
        if (total <= 0) {
            return null;
        }

        double roll = rnd.nextDouble() * total;
        T last = null;
        for (T item : items) {
            double w = weight.applyAsDouble(item);
            if (w <= 0) {
                continue;
            }
            last = item;
            roll -= w;
            if (roll < 0) {
                return item;
            }
        }
        return last;
    }

    private ChunkPos randomChunk(RandomSource rnd, int cx, int cz, int separation) {
        int size = settings.cellSize();
        int lo = Math.min(separation, (size - 1) / 2);
        int hi = size - 1 - lo;
        return new ChunkPos(
            cx * size + lo + rnd.nextInt(hi - lo + 1),
            cz * size + lo + rnd.nextInt(hi - lo + 1)
        );
    }

    private boolean biomeAllows(Entry entry, ChunkPos chunk) {
        Holder<Biome> biome = biomeSource.getNoiseBiome(
            chunk.getMiddleBlockX() >> 2,
            SAMPLE_QUART_Y,
            chunk.getMiddleBlockZ() >> 2,
            sampler
        );
        for (StructureSet.StructureSelectionEntry selection : entry.set().structures()) {
            if (selection.structure().value().biomes().contains(biome)) {
                return true;
            }
        }
        return false;
    }

    private RandomSource random(int cx, int cz, long salt) {
        long h = mix(seed ^ salt);
        h = mix(h + cx * 0x9E3779B97F4A7C15L);
        h = mix(h + cz * 0xC2B2AE3D27D4EB4FL);
        return new LegacyRandomSource(h);
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long pack(int x, int z) {
        return (long) x & 0xFFFFFFFFL | ((long) z & 0xFFFFFFFFL) << 32;
    }

    private static int unpackX(long key) {
        return (int) key;
    }

    private static int unpackZ(long key) {
        return (int) (key >>> 32);
    }
}
