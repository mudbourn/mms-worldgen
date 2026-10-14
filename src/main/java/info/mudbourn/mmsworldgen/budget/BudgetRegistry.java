package info.mudbourn.mmsworldgen.budget;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import info.mudbourn.mmsworldgen.config.WorldgenConfig;
import info.mudbourn.mmsworldgen.mixin.ChunkGeneratorStructureStateAccessor;
import info.mudbourn.mmsworldgen.mixin.StructureSetAccessor;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class BudgetRegistry {

    public enum Status { MANAGED, EXEMPT, UNMANAGED_PLACEMENT, SKIPPED }

    public record Row(Identifier id, Status status, String group, double weight) {}

    public record Binding(CellArbiter arbiter, List<Row> rows) {}

    private static final Map<Identifier, Binding> BINDINGS = new ConcurrentHashMap<>();
    private static final Map<Identifier, Map<StructureSet, BudgetPlacement>> PLACEMENTS = new ConcurrentHashMap<>();

    private static volatile WorldgenConfig config = new WorldgenConfig();
    private static volatile Map<StructureSet, BudgetPlacement> active = Map.of();

    private BudgetRegistry() {}

    public static void setConfig(WorldgenConfig loaded) {
        config = loaded;
    }

    public static StructurePlacement placementFor(StructureSet set) {
        return active.get(set);
    }

    public static Binding binding(Identifier dimension) {
        return BINDINGS.get(dimension);
    }

    public static synchronized void clear() {
        BINDINGS.clear();
        PLACEMENTS.clear();
        publish();
    }

    // Resolves and publishes the budget placements for one dimension.
    public static synchronized void bind(Identifier dimension, ChunkGeneratorStructureState state) {
        BINDINGS.remove(dimension);
        PLACEMENTS.remove(dimension);
        publish();

        WorldgenConfig.Dimension dim = config.dimensions.get(dimension.toString());
        if (dim == null) {
            return;
        }

        int cellSize = dim.cellSize;
        List<Row> rows = new ArrayList<>();
        Map<String, List<CellArbiter.Entry>> byGroup = new LinkedHashMap<>();
        Map<String, Double> groupWeights = new LinkedHashMap<>();
        for (WorldgenConfig.Group group : dim.groups) {
            groupWeights.put(group.name, group.weight);
        }
        Map<Identifier, RandomSpreadStructurePlacement> originals = new LinkedHashMap<>();

        List<Holder<StructureSet>> holders = new ArrayList<>(state.possibleStructureSets());
        holders.sort(Comparator.comparing(h -> h.unwrapKey().map(key -> key.identifier().toString()).orElse("")));
        for (Holder<StructureSet> holder : holders) {
            Identifier id = holder.unwrapKey().map(key -> key.identifier()).orElse(null);
            if (id == null) {
                continue;
            }
            StructureSet set = holder.value();
            if (matchesAny(dim.exempt, id)) {
                rows.add(new Row(id, Status.EXEMPT, "", 0));
                continue;
            }
            if (!(((StructureSetAccessor) (Object) set).mmsworldgen$placement() instanceof RandomSpreadStructurePlacement spread)) {
                rows.add(new Row(id, Status.UNMANAGED_PLACEMENT, "", 0));
                continue;
            }
            Identifier owner = otherOwner(dimension, set);
            if (owner != null) {
                MmsWorldgen.LOG.warn("Structure set {} is already managed in {}, skipping it in {}", id, owner, dimension);
                rows.add(new Row(id, Status.SKIPPED, "", 0));
                continue;
            }

            WorldgenConfig.Group configured = firstGroup(dim, id);
            String groupName = configured != null ? configured.name : id.getNamespace();
            groupWeights.putIfAbsent(groupName, dim.defaultWeight);
            double weight = configured != null ? setWeight(configured, id) : 1.0;
            int separation = Math.min(spread.separation(), cellSize - 1);

            byGroup.computeIfAbsent(groupName, k -> new ArrayList<>())
                .add(new CellArbiter.Entry(id, groupName, weight, separation, set));
            originals.put(id, spread);
            rows.add(new Row(id, Status.MANAGED, groupName, weight));
        }

        List<CellArbiter.Group> groups = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (WorldgenConfig.Group group : dim.groups) {
            if (byGroup.containsKey(group.name) && !names.contains(group.name)) {
                names.add(group.name);
            }
        }
        byGroup.keySet().stream().filter(name -> !names.contains(name)).sorted().forEach(names::add);
        for (String name : names) {
            groups.add(new CellArbiter.Group(name, groupWeights.get(name), byGroup.get(name)));
        }

        CellArbiter arbiter = new CellArbiter(
            state.getLevelSeed(),
            new CellArbiter.Settings(cellSize, dim.emptyChance, dim.noveltyRadius, dim.noveltyFalloff, dim.retries),
            groups,
            ((ChunkGeneratorStructureStateAccessor) state).mmsworldgen$biomeSource(),
            state.randomState().sampler()
        );

        Map<StructureSet, BudgetPlacement> placements = new IdentityHashMap<>();
        for (CellArbiter.Group group : groups) {
            for (CellArbiter.Entry entry : group.entries()) {
                placements.put(entry.set(), new BudgetPlacement(originals.get(entry.id()), entry.id(), arbiter));
            }
        }

        BINDINGS.put(dimension, new Binding(arbiter, rows));
        PLACEMENTS.put(dimension, placements);
        publish();
        MmsWorldgen.LOG.info("Bound {} structure sets in {} (cell size {})", placements.size(), dimension, cellSize);
    }

    private static Identifier otherOwner(Identifier dimension, StructureSet set) {
        for (Map.Entry<Identifier, Map<StructureSet, BudgetPlacement>> entry : PLACEMENTS.entrySet()) {
            if (!entry.getKey().equals(dimension) && entry.getValue().containsKey(set)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static void publish() {
        Map<StructureSet, BudgetPlacement> merged = new IdentityHashMap<>();
        PLACEMENTS.values().forEach(merged::putAll);
        active = merged;
    }

    private static WorldgenConfig.Group firstGroup(WorldgenConfig.Dimension dim, Identifier id) {
        for (WorldgenConfig.Group group : dim.groups) {
            if (matchesAny(group.sets, id)) {
                return group;
            }
        }
        return null;
    }

    private static double setWeight(WorldgenConfig.Group group, Identifier id) {
        for (Map.Entry<String, Double> entry : group.setWeights.entrySet()) {
            if (glob(entry.getKey()).matcher(id.toString()).matches()) {
                return entry.getValue();
            }
        }
        return 1.0;
    }

    private static boolean matchesAny(List<String> patterns, Identifier id) {
        for (String pattern : patterns) {
            if (glob(pattern).matcher(id.toString()).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString());
    }
}
