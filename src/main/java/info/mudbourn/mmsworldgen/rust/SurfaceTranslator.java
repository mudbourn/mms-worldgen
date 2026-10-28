package info.mudbourn.mmsworldgen.rust;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.InclusiveRange;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public final class SurfaceTranslator {
    static final int FIXED_STATES = 9;

    private static final int R_BLOCK = 0;
    private static final int R_SEQUENCE = 1;
    private static final int R_TEST = 2;
    private static final int R_BANDLANDS = 3;
    private static final int R_BANDS = 4;
    private static final int R_GRID = 5;
    private static final int R_BY_BIOME = 6;

    private static final int C_BIOME = 0;
    private static final int C_NOISE = 1;
    private static final int C_GRADIENT = 2;
    private static final int C_Y = 3;
    private static final int C_WATER = 4;
    private static final int C_TEMPERATURE = 5;
    private static final int C_STEEP = 6;
    private static final int C_HOLE = 7;
    private static final int C_ABOVE_PRELIMINARY = 8;
    private static final int C_STONE_DEPTH = 9;
    private static final int C_NOT = 10;
    private static final int C_ALL = 11;
    private static final int C_ANY = 12;
    private static final int C_SLOPE = 13;

    private static final int BIOME_BADLANDS_EXTENSION = 1;
    private static final int BIOME_ICEBERG_EXTENSION = 2;

    private static final String TERRABLENDER_NAMESPACED =
        "terrablender.worldgen.surface.NamespacedSurfaceRuleSource";
    private static final String LITHOSTITCHED_MERGED =
        "dev.worldgen.lithostitched.worldgen.surface.rule.TransientMergedRule";
    private static final String LITHOSTITCHED_REFERENCE =
        "dev.worldgen.lithostitched.worldgen.surface.rule.ReferenceRule";
    private static final String LITHOSTITCHED_ALL =
        "dev.worldgen.lithostitched.worldgen.surface.condition.AllOfCondition";
    private static final String LITHOSTITCHED_ANY =
        "dev.worldgen.lithostitched.worldgen.surface.condition.AnyOfCondition";
    private static final String LITHOSTITCHED_BIOME =
        "dev.worldgen.lithostitched.worldgen.surface.condition.BiomeCondition";
    private static final String LITHOSTITCHED_SLOPE =
        "dev.worldgen.lithostitched.worldgen.surface.condition.SlopeCondition";
    private static final String BWG_PACKAGE = "net.potionstudios.biomeswevegone.world.level.levelgen.surfacerules.";

    private static final List<ResourceKey<NormalNoise.NoiseParameters>> FIXED_NOISES = List.of(
        Noises.SURFACE,
        Noises.SURFACE_SECONDARY,
        Noises.CLAY_BANDS_OFFSET,
        Noises.BADLANDS_PILLAR,
        Noises.BADLANDS_PILLAR_ROOF,
        Noises.BADLANDS_SURFACE,
        Noises.ICEBERG_PILLAR,
        Noises.ICEBERG_PILLAR_ROOF,
        Noises.ICEBERG_SURFACE
    );

    // Flat arrays describing a translated surface rule, in the layout the native side reads.
    public record Translation(
        int[] ruleInts,
        int[] condInts,
        double[] condDoubles,
        int[] lists,
        int root,
        int defaultBlock,
        String[] noiseKeys,
        int[] noiseOctaves,
        int[] noiseAmpEnds,
        double[] noiseAmps,
        String[] gradientNames,
        int[] biomeInts,
        List<BlockState> states
    ) {
    }

    // Raised when a surface rule holds a node the native side does not know.
    public static final class UnsupportedException extends Exception {
        public UnsupportedException(String message) {
            super(message);
        }
    }

    private final Registry<Biome> biomes;
    private final Registry<NormalNoise.NoiseParameters> noiseRegistry;
    private final WorldGenerationContext context;
    private final PositionalRandomFactory noiseRandom;
    private final List<BlockState> states = new ArrayList<>();
    private final IdentityHashMap<BlockState, Integer> stateIds = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Integer> ruleIds = new IdentityHashMap<>();
    private final IdentityHashMap<Object, Integer> condIds = new IdentityHashMap<>();
    private final Map<String, Integer> noiseIds = new LinkedHashMap<>();
    private final List<NormalNoise.NoiseParameters> noiseParameters = new ArrayList<>();
    private final List<String> gradientNames = new ArrayList<>();
    private final IntArrayList ruleInts = new IntArrayList();
    private final IntArrayList condInts = new IntArrayList();
    private final DoubleArrayList condDoubles = new DoubleArrayList();
    private final IntArrayList lists = new IntArrayList();

    private SurfaceTranslator(
        Registry<Biome> biomes,
        Registry<NormalNoise.NoiseParameters> noiseRegistry,
        WorldGenerationContext context,
        PositionalRandomFactory noiseRandom
    ) {
        this.biomes = biomes;
        this.noiseRegistry = noiseRegistry;
        this.context = context;
        this.noiseRandom = noiseRandom;
    }

    // Translates a dimension's surface rule into flat arrays, or throws when a node is unknown.
    public static Translation translate(
        NoiseGeneratorSettings settings,
        SurfaceSystem system,
        Registry<Biome> biomes,
        Registry<NormalNoise.NoiseParameters> noises,
        WorldGenerationContext context
    ) throws UnsupportedException {
        SurfaceTranslator translator = new SurfaceTranslator(biomes, noises, context, system.noiseRandom);
        translator.state(Blocks.WHITE_TERRACOTTA.defaultBlockState());
        translator.state(Blocks.ORANGE_TERRACOTTA.defaultBlockState());
        translator.state(Blocks.TERRACOTTA.defaultBlockState());
        translator.state(Blocks.YELLOW_TERRACOTTA.defaultBlockState());
        translator.state(Blocks.BROWN_TERRACOTTA.defaultBlockState());
        translator.state(Blocks.RED_TERRACOTTA.defaultBlockState());
        translator.state(Blocks.LIGHT_GRAY_TERRACOTTA.defaultBlockState());
        translator.state(Blocks.PACKED_ICE.defaultBlockState());
        translator.state(Blocks.SNOW_BLOCK.defaultBlockState());
        for (ResourceKey<NormalNoise.NoiseParameters> key : FIXED_NOISES) {
            translator.noise(key);
        }
        int defaultBlock = translator.state(settings.defaultBlock());
        int root = translator.rule(settings.surfaceRule());
        return translator.finish(root, defaultBlock);
    }

    private Translation finish(int root, int defaultBlock) {
        int count = noiseParameters.size();
        int[] octaves = new int[count];
        int[] ends = new int[count];
        DoubleArrayList amplitudes = new DoubleArrayList();
        for (int i = 0; i < count; i++) {
            NormalNoise.NoiseParameters parameters = noiseParameters.get(i);
            octaves[i] = parameters.firstOctave();
            amplitudes.addAll(parameters.amplitudes());
            ends[i] = amplitudes.size();
        }
        return new Translation(
            ruleInts.toIntArray(),
            condInts.toIntArray(),
            condDoubles.toDoubleArray(),
            lists.toIntArray(),
            root,
            defaultBlock,
            noiseIds.keySet().toArray(new String[0]),
            octaves,
            ends,
            amplitudes.toDoubleArray(),
            gradientNames.toArray(new String[0]),
            biomeTable(),
            List.copyOf(states)
        );
    }

    private int[] biomeTable() {
        int[] table = new int[biomes.size() * 3];
        for (int id = 0; id < biomes.size(); id++) {
            Biome biome = biomes.byId(id);
            if (biome == null) {
                continue;
            }
            String key = String.valueOf(biomes.getKey(biome));
            int flags = 0;
            if (key.equals("minecraft:eroded_badlands")
                || key.equals("biomeswevegone:shattered_glacier")
                || key.equals("biomeswevegone:eroded_borealis")) {
                flags |= BIOME_BADLANDS_EXTENSION;
            }
            if (key.equals("minecraft:frozen_ocean") || key.equals("minecraft:deep_frozen_ocean")) {
                flags |= BIOME_ICEBERG_EXTENSION;
            }
            table[id * 3] = Float.floatToRawIntBits(biome.getBaseTemperature());
            table[id * 3 + 1] = biome.climateSettings.temperatureModifier() == Biome.TemperatureModifier.FROZEN ? 1 : 0;
            table[id * 3 + 2] = flags;
        }
        return table;
    }

    private int state(BlockState state) {
        Integer known = stateIds.get(state);
        if (known != null) {
            return known;
        }
        states.add(state);
        stateIds.put(state, states.size() - 1);
        return states.size() - 1;
    }

    private int noise(ResourceKey<NormalNoise.NoiseParameters> key) throws UnsupportedException {
        String name = key.identifier().toString();
        Integer known = noiseIds.get(name);
        if (known != null) {
            return known;
        }
        NormalNoise.NoiseParameters parameters = noiseRegistry.getValue(key);
        if (parameters == null) {
            throw new UnsupportedException("missing noise " + name);
        }
        noiseIds.put(name, noiseParameters.size());
        noiseParameters.add(parameters);
        return noiseParameters.size() - 1;
    }

    private int emitRule(int kind, int a, int b, int c, int d) {
        ruleInts.add(kind);
        ruleInts.add(a);
        ruleInts.add(b);
        ruleInts.add(c);
        ruleInts.add(d);
        return ruleInts.size() / 5 - 1;
    }

    private int emitCond(int kind, int a, int b, int c, int d, double min, double max) {
        condInts.add(kind);
        condInts.add(a);
        condInts.add(b);
        condInts.add(c);
        condInts.add(d);
        condDoubles.add(min);
        condDoubles.add(max);
        return condInts.size() / 5 - 1;
    }

    private int emitCond(int kind, int a, int b, int c, int d) {
        return emitCond(kind, a, b, c, d, 0.0, 0.0);
    }

    private int listOf(int[] values) {
        int start = lists.size();
        for (int value : values) {
            lists.add(value);
        }
        return start;
    }

    private int rule(SurfaceRules.RuleSource source) throws UnsupportedException {
        Integer known = ruleIds.get(source);
        if (known != null) {
            return known;
        }
        int id = buildRule(source);
        ruleIds.put(source, id);
        return id;
    }

    private int sequence(List<SurfaceRules.RuleSource> sources) throws UnsupportedException {
        int[] children = new int[sources.size()];
        for (int i = 0; i < children.length; i++) {
            children[i] = rule(sources.get(i));
        }
        return emitRule(R_SEQUENCE, listOf(children), children.length, 0, 0);
    }

    private int buildRule(SurfaceRules.RuleSource source) throws UnsupportedException {
        if (source instanceof SurfaceRules.BlockRuleSource block) {
            return emitRule(R_BLOCK, state(block.resultState()), 0, 0, 0);
        }
        if (source instanceof SurfaceRules.SequenceRuleSource sequence) {
            return sequence(sequence.sequence());
        }
        if (source instanceof SurfaceRules.TestRuleSource test) {
            int condition = cond(test.ifTrue());
            return emitRule(R_TEST, condition, rule(test.thenRun()), 0, 0);
        }
        if (source == SurfaceRules.Bandlands.INSTANCE) {
            return emitRule(R_BANDLANDS, 0, 0, 0, 0);
        }
        return modRule(source);
    }

    @SuppressWarnings("unchecked")
    private int modRule(SurfaceRules.RuleSource source) throws UnsupportedException {
        String name = source.getClass().getName();
        if (name.equals(TERRABLENDER_NAMESPACED)) {
            int base = rule((SurfaceRules.RuleSource) component(source, "base"));
            Map<String, SurfaceRules.RuleSource> sources =
                (Map<String, SurfaceRules.RuleSource>) component(source, "sources");
            Map<String, Integer> byNamespace = new LinkedHashMap<>();
            for (Map.Entry<String, SurfaceRules.RuleSource> entry : sources.entrySet()) {
                byNamespace.put(entry.getKey(), rule(entry.getValue()));
            }
            int[] targets = new int[biomes.size()];
            for (int id = 0; id < targets.length; id++) {
                Biome biome = biomes.byId(id);
                Integer target = biome == null ? null : byNamespace.get(biomes.getKey(biome).getNamespace());
                targets[id] = target == null ? -1 : target;
            }
            return emitRule(R_BY_BIOME, base, listOf(targets), 0, 0);
        }
        if (name.equals(LITHOSTITCHED_MERGED)) {
            List<SurfaceRules.RuleSource> rules = (List<SurfaceRules.RuleSource>) component(source, "rules");
            if (rules.size() == 1) {
                return rule(rules.getFirst());
            }
            List<SurfaceRules.RuleSource> all = new ArrayList<>(rules);
            all.add((SurfaceRules.RuleSource) component(source, "original"));
            return sequence(all);
        }
        if (name.equals(LITHOSTITCHED_REFERENCE)) {
            HolderSet<SurfaceRules.RuleSource> rules =
                (HolderSet<SurfaceRules.RuleSource>) component(source, "rules");
            List<SurfaceRules.RuleSource> all = new ArrayList<>();
            for (int i = 0; i < rules.size(); i++) {
                all.add(rules.get(i).value());
            }
            return sequence(all);
        }
        if (name.equals(BWG_PACKAGE + "BetweenRepeatingNoiseRange")) {
            return rule((SurfaceRules.RuleSource) field(source, "rule"));
        }
        if (name.equals(BWG_PACKAGE + "WeightedRuleSource")) {
            return weighted((WeightedList<SurfaceRules.RuleSource>) component(source, "ruleSources"));
        }
        if (name.equals(BWG_PACKAGE + "BandsRuleSource")) {
            return bands(source);
        }
        throw new UnsupportedException(name);
    }

    private int weighted(WeightedList<SurfaceRules.RuleSource> sources) throws UnsupportedException {
        int[] children = new int[256];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                RandomSource random = noiseRandom.at(x, 0, z);
                Optional<SurfaceRules.RuleSource> chosen = sources.getRandom(random);
                if (chosen.isEmpty()) {
                    throw new UnsupportedException("weighted rule source without a choice");
                }
                children[x * 16 + z] = rule(chosen.get());
            }
        }
        return emitRule(R_GRID, listOf(children), 0, 0, 0);
    }

    @SuppressWarnings("unchecked")
    private int bands(SurfaceRules.RuleSource source) throws UnsupportedException {
        WeightedList<BlockState> bandStates = (WeightedList<BlockState>) component(source, "bandStates");
        IntProvider bandSize = (IntProvider) component(source, "bandSizeProvider");
        IntProvider bandsCount = (IntProvider) component(source, "bandsCountProvider");
        float frequency = (Float) component(source, "frequency");
        int noiseScale = (Integer) component(source, "noiseScale");
        RandomSource random = noiseRandom.at(0, 0, 0);
        List<BlockState> chosen = new ArrayList<>();
        int count = bandsCount.sample(random);
        for (int band = 0; band < count; band++) {
            int size = bandSize.sample(random);
            BlockState state = bandStates.getRandomOrThrow(random);
            for (int i = 0; i < size; i++) {
                chosen.add(state);
            }
        }
        if (chosen.size() < 2) {
            throw new UnsupportedException("bands rule with fewer than two states");
        }
        int[] indices = new int[chosen.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = state(chosen.get(i));
        }
        return emitRule(R_BANDS, listOf(indices), indices.length, Float.floatToRawIntBits(frequency), noiseScale);
    }

    private int cond(SurfaceRules.ConditionSource source) throws UnsupportedException {
        Integer known = condIds.get(source);
        if (known != null) {
            return known;
        }
        int id = buildCond(source);
        condIds.put(source, id);
        return id;
    }

    private int biomeSet(Predicate<Biome> test) {
        int[] flags = new int[biomes.size()];
        for (int id = 0; id < flags.length; id++) {
            Biome biome = biomes.byId(id);
            flags[id] = biome != null && test.test(biome) ? 1 : 0;
        }
        return emitCond(C_BIOME, listOf(flags), 0, 0, 0);
    }

    private int buildCond(SurfaceRules.ConditionSource source) throws UnsupportedException {
        if (source instanceof SurfaceRules.BiomeConditionSource biome) {
            List<Biome> wanted = new ArrayList<>();
            for (ResourceKey<Biome> key : biome.biomes) {
                Biome value = biomes.getValue(key);
                if (value != null) {
                    wanted.add(value);
                }
            }
            return biomeSet(candidate -> wanted.contains(candidate));
        }
        if (source instanceof SurfaceRules.NoiseThresholdConditionSource noise) {
            return emitCond(C_NOISE, noise(noise.noise()), 0, 0, 0, noise.minThreshold(), noise.maxThreshold());
        }
        if (source instanceof SurfaceRules.VerticalGradientConditionSource gradient) {
            gradientNames.add(gradient.randomName().toString());
            return emitCond(
                C_GRADIENT,
                gradientNames.size() - 1,
                gradient.trueAtAndBelow().resolveY(context),
                gradient.falseAtAndAbove().resolveY(context),
                0
            );
        }
        if (source instanceof SurfaceRules.YConditionSource y) {
            return emitCond(
                C_Y,
                y.anchor().resolveY(context),
                y.surfaceDepthMultiplier(),
                y.addStoneDepth() ? 1 : 0,
                0
            );
        }
        if (source instanceof SurfaceRules.WaterConditionSource water) {
            return emitCond(
                C_WATER,
                water.offset(),
                water.surfaceDepthMultiplier(),
                water.addStoneDepth() ? 1 : 0,
                0
            );
        }
        if (source instanceof SurfaceRules.StoneDepthCheck depth) {
            return emitCond(
                C_STONE_DEPTH,
                depth.offset(),
                depth.addSurfaceDepth() ? 1 : 0,
                depth.secondaryDepthRange(),
                depth.surfaceType() == CaveSurface.CEILING ? 1 : 0
            );
        }
        if (source instanceof SurfaceRules.NotConditionSource not) {
            return emitCond(C_NOT, cond(not.target()), 0, 0, 0);
        }
        if (source == SurfaceRules.Temperature.INSTANCE) {
            return emitCond(C_TEMPERATURE, 0, 0, 0, 0);
        }
        if (source == SurfaceRules.Steep.INSTANCE) {
            return emitCond(C_STEEP, 0, 0, 0, 0);
        }
        if (source == SurfaceRules.Hole.INSTANCE) {
            return emitCond(C_HOLE, 0, 0, 0, 0);
        }
        if (source == SurfaceRules.AbovePreliminarySurface.INSTANCE) {
            return emitCond(C_ABOVE_PRELIMINARY, 0, 0, 0, 0);
        }
        return modCond(source);
    }

    @SuppressWarnings("unchecked")
    private int modCond(SurfaceRules.ConditionSource source) throws UnsupportedException {
        String name = source.getClass().getName();
        if (name.equals(LITHOSTITCHED_ALL) || name.equals(LITHOSTITCHED_ANY)) {
            List<SurfaceRules.ConditionSource> conditions =
                (List<SurfaceRules.ConditionSource>) component(source, "conditions");
            int[] children = new int[conditions.size()];
            for (int i = 0; i < children.length; i++) {
                children[i] = cond(conditions.get(i));
            }
            return emitCond(name.equals(LITHOSTITCHED_ALL) ? C_ALL : C_ANY, listOf(children), children.length, 0, 0);
        }
        if (name.equals(LITHOSTITCHED_BIOME)) {
            HolderSet<Biome> set = (HolderSet<Biome>) component(source, "biomes");
            return biomeSet(candidate -> set.contains(biomes.wrapAsHolder(candidate)));
        }
        if (name.equals(LITHOSTITCHED_SLOPE)) {
            InclusiveRange<Integer> range = (InclusiveRange<Integer>) component(source, "threshold");
            return emitCond(C_SLOPE, range.minInclusive(), range.maxInclusive(), 0, 0);
        }
        throw new UnsupportedException(name);
    }

    private static Object component(Object record, String name) throws UnsupportedException {
        try {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                if (component.getName().equals(name)) {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    return accessor.invoke(record);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new UnsupportedException(record.getClass().getName() + "." + name + ": " + e);
        }
        throw new UnsupportedException(record.getClass().getName() + " has no component " + name);
    }

    private static Object field(Object owner, String name) throws UnsupportedException {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new UnsupportedException(owner.getClass().getName() + "." + name + ": " + e);
        }
    }
}
