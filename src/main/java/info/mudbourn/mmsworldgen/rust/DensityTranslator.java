package info.mudbourn.mmsworldgen.rust;

import info.mudbourn.mmsworldgen.router.RouterDump;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public final class DensityTranslator {
    private static final int CONSTANT = 0;
    private static final int ADD = 1;
    private static final int MUL = 2;
    private static final int MIN = 3;
    private static final int MAX = 4;
    private static final int MUL_OR_ADD = 5;
    private static final int MAPPED = 6;
    private static final int CLAMP = 7;
    private static final int MARKER = 8;
    private static final int NOISE = 9;
    private static final int SHIFT_A = 10;
    private static final int SHIFT_B = 11;
    private static final int SHIFT = 12;
    private static final int SHIFTED_NOISE = 13;
    private static final int WEIRD_SCALED = 14;
    private static final int RANGE_CHOICE = 15;
    private static final int Y_GRADIENT = 16;
    private static final int FIND_TOP_SURFACE = 17;
    private static final int BLEND_ALPHA = 18;
    private static final int BLEND_OFFSET = 19;
    private static final int BLEND_DENSITY = 20;
    private static final int BEARDIFIER = 21;
    private static final int BLENDED_NOISE = 22;
    private static final int SPLINE = 23;

    // Flat arrays describing a translated router, in the layout the native side reads.
    public record Translation(
        int[] nodeInts,
        double[] nodeDoubles,
        String[] noiseKeys,
        int[] noiseOctaves,
        int[] noiseAmpEnds,
        double[] noiseAmps,
        double[] blended,
        int[] splineInts,
        double[] splineConsts,
        int[] pointChildren,
        double[] pointValues,
        int[] roots
    ) {
    }

    // Raised when a router holds a density function type the native side does not know.
    public static final class UnsupportedException extends Exception {
        public UnsupportedException(String message) {
            super(message);
        }
    }

    private final IdentityHashMap<Object, Integer> nodeIds = new IdentityHashMap<>();
    private final IdentityHashMap<BlendedNoise, Integer> blendedIds = new IdentityHashMap<>();
    private final Map<String, Integer> noiseIds = new LinkedHashMap<>();
    private final List<NormalNoise.NoiseParameters> noiseParameters = new ArrayList<>();
    private final IntArrayList nodeInts = new IntArrayList();
    private final DoubleArrayList nodeDoubles = new DoubleArrayList();
    private final DoubleArrayList blended = new DoubleArrayList();
    private final IntArrayList splineInts = new IntArrayList();
    private final DoubleArrayList splineConsts = new DoubleArrayList();
    private final IntArrayList pointChildren = new IntArrayList();
    private final DoubleArrayList pointValues = new DoubleArrayList();

    private DensityTranslator() {
    }

    // Translates the unseeded router into flat arrays, or throws when a type is unknown.
    public static Translation translate(NoiseRouter router) throws UnsupportedException {
        DensityTranslator translator = new DensityTranslator();
        int[] roots = new int[RouterDump.FIELDS.size()];
        for (int i = 0; i < roots.length; i++) {
            roots[i] = translator.visit(RouterDump.FIELDS.get(i).getter().apply(router));
        }
        return translator.finish(roots);
    }

    private Translation finish(int[] roots) {
        int count = noiseParameters.size();
        String[] keys = noiseIds.keySet().toArray(new String[0]);
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
            nodeInts.toIntArray(),
            nodeDoubles.toDoubleArray(),
            keys,
            octaves,
            ends,
            amplitudes.toDoubleArray(),
            blended.toDoubleArray(),
            splineInts.toIntArray(),
            splineConsts.toDoubleArray(),
            pointChildren.toIntArray(),
            pointValues.toDoubleArray(),
            roots
        );
    }

    private int emit(int op, int a, int b, int c, int d, double first, double second) {
        nodeInts.add(op);
        nodeInts.add(a);
        nodeInts.add(b);
        nodeInts.add(c);
        nodeInts.add(d);
        nodeDoubles.add(first);
        nodeDoubles.add(second);
        return nodeInts.size() / 5 - 1;
    }

    private int emit(int op, int a, int b, int c, int d) {
        return emit(op, a, b, c, d, 0.0, 0.0);
    }

    private int visit(DensityFunction function) throws UnsupportedException {
        Integer known = nodeIds.get(function);
        if (known != null) {
            return known;
        }
        int id = build(function);
        nodeIds.put(function, id);
        return id;
    }

    private int build(DensityFunction function) throws UnsupportedException {
        return switch (function) {
            case DensityFunctions.Constant c -> emit(CONSTANT, 0, 0, 0, 0, c.value(), 0.0);
            case DensityFunctions.HolderHolder h -> visit(h.function().value());
            case DensityFunctions.Marker m -> emit(MARKER, visit(m.wrapped()), markerKind(m.type()), 0, 0);
            case DensityFunctions.MulOrAdd m -> emit(
                MUL_OR_ADD,
                visit(m.input()),
                m.specificType() == DensityFunctions.MulOrAdd.Type.ADD ? 1 : 0,
                0,
                0,
                m.argument(),
                0.0
            );
            case DensityFunctions.Ap2 a -> ap2(a);
            case DensityFunctions.Mapped m -> emit(MAPPED, visit(m.input()), mappedKind(m.type()), 0, 0);
            case DensityFunctions.Clamp c -> emit(CLAMP, visit(c.input()), 0, 0, 0, c.minValue(), c.maxValue());
            case DensityFunctions.Noise n -> emit(NOISE, noise(n.noise()), 0, 0, 0, n.xzScale(), n.yScale());
            case DensityFunctions.ShiftA s -> emit(SHIFT_A, noise(s.offsetNoise()), 0, 0, 0);
            case DensityFunctions.ShiftB s -> emit(SHIFT_B, noise(s.offsetNoise()), 0, 0, 0);
            case DensityFunctions.Shift s -> emit(SHIFT, noise(s.offsetNoise()), 0, 0, 0);
            case DensityFunctions.ShiftedNoise s -> emit(
                SHIFTED_NOISE,
                visit(s.shiftX()),
                visit(s.shiftY()),
                visit(s.shiftZ()),
                noise(s.noise()),
                s.xzScale(),
                s.yScale()
            );
            case DensityFunctions.WeirdScaledSampler w -> emit(
                WEIRD_SCALED,
                visit(w.input()),
                noise(w.noise()),
                w.rarityValueMapper() == DensityFunctions.WeirdScaledSampler.RarityValueMapper.TYPE2 ? 1 : 0,
                0
            );
            case DensityFunctions.RangeChoice r -> emit(
                RANGE_CHOICE,
                visit(r.input()),
                visit(r.whenInRange()),
                visit(r.whenOutOfRange()),
                0,
                r.minInclusive(),
                r.maxExclusive()
            );
            case DensityFunctions.YClampedGradient g -> emit(
                Y_GRADIENT,
                g.fromY(),
                g.toY(),
                0,
                0,
                g.fromValue(),
                g.toValue()
            );
            case DensityFunctions.FindTopSurface f -> emit(
                FIND_TOP_SURFACE,
                visit(f.density()),
                visit(f.upperBound()),
                f.lowerBound(),
                f.cellHeight()
            );
            case DensityFunctions.BlendAlpha a -> emit(BLEND_ALPHA, 0, 0, 0, 0);
            case DensityFunctions.BlendOffset o -> emit(BLEND_OFFSET, 0, 0, 0, 0);
            case DensityFunctions.BlendDensity d -> emit(BLEND_DENSITY, visit(d.input()), 0, 0, 0);
            case DensityFunctions.BeardifierMarker b -> emit(BEARDIFIER, 0, 0, 0, 0);
            case BlendedNoise b -> emit(BLENDED_NOISE, blendedNoise(b), 0, 0, 0);
            case DensityFunctions.Spline s -> emit(SPLINE, spline(s.spline()), 0, 0, 0);
            default -> throw new UnsupportedException(function.getClass().getName());
        };
    }

    private int ap2(DensityFunctions.Ap2 function) throws UnsupportedException {
        DensityFunctions.TwoArgumentSimpleFunction.Type type = function.type();
        DensityFunction first = function.argument1();
        DensityFunction second = function.argument2();
        boolean add = type == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD;
        boolean mul = type == DensityFunctions.TwoArgumentSimpleFunction.Type.MUL;
        if (add || mul) {
            if (first instanceof DensityFunctions.Constant constant) {
                return emit(MUL_OR_ADD, visit(second), add ? 1 : 0, 0, 0, constant.value(), 0.0);
            }
            if (second instanceof DensityFunctions.Constant constant) {
                return emit(MUL_OR_ADD, visit(first), add ? 1 : 0, 0, 0, constant.value(), 0.0);
            }
        }
        int op = switch (type) {
            case ADD -> ADD;
            case MUL -> MUL;
            case MIN -> MIN;
            case MAX -> MAX;
        };
        return emit(op, visit(first), visit(second), 0, 0);
    }

    private static int markerKind(DensityFunctions.Marker.Type type) {
        return switch (type) {
            case Interpolated -> 0;
            case FlatCache -> 1;
            case Cache2D -> 2;
            case CacheOnce -> 3;
            case CacheAllInCell -> 4;
        };
    }

    private static int mappedKind(DensityFunctions.Mapped.Type type) {
        return switch (type) {
            case ABS -> 0;
            case SQUARE -> 1;
            case CUBE -> 2;
            case HALF_NEGATIVE -> 3;
            case QUARTER_NEGATIVE -> 4;
            case INVERT -> 5;
            case SQUEEZE -> 6;
        };
    }

    private int noise(DensityFunction.NoiseHolder holder) throws UnsupportedException {
        Holder<NormalNoise.NoiseParameters> data = holder.noiseData();
        String key = data.unwrapKey()
            .orElseThrow(() -> new UnsupportedException("noise without a registry key"))
            .identifier()
            .toString();
        Integer known = noiseIds.get(key);
        if (known != null) {
            return known;
        }
        int id = noiseParameters.size();
        noiseIds.put(key, id);
        noiseParameters.add(data.value());
        return id;
    }

    private int blendedNoise(BlendedNoise noise) {
        Integer known = blendedIds.get(noise);
        if (known != null) {
            return known;
        }
        int id = blended.size() / 5;
        blended.add(noise.xzScale);
        blended.add(noise.yScale);
        blended.add(noise.xzFactor);
        blended.add(noise.yFactor);
        blended.add(noise.smearScaleMultiplier);
        blendedIds.put(noise, id);
        return id;
    }

    private int spline(CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> spline)
        throws UnsupportedException {
        if (spline instanceof CubicSpline.Constant<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> constant) {
            return addSpline(0, 0, 0, 0, constant.value());
        }
        if (spline instanceof CubicSpline.Multipoint<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> multipoint) {
            int coordinate = visit(multipoint.coordinate().function().value());
            int count = multipoint.locations().length;
            int[] children = new int[count];
            for (int i = 0; i < count; i++) {
                children[i] = spline(multipoint.values().get(i));
            }
            int start = pointChildren.size();
            for (int i = 0; i < count; i++) {
                pointChildren.add(children[i]);
                pointValues.add(multipoint.locations()[i]);
                pointValues.add(multipoint.derivatives()[i]);
            }
            return addSpline(1, coordinate, count, start, 0.0);
        }
        throw new UnsupportedException(spline.getClass().getName());
    }

    private int addSpline(int kind, int coordinate, int count, int start, double constant) {
        splineInts.add(kind);
        splineInts.add(coordinate);
        splineInts.add(count);
        splineInts.add(start);
        splineConsts.add(constant);
        return splineInts.size() / 4 - 1;
    }
}
