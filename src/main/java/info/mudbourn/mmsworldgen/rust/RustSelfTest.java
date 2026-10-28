package info.mudbourn.mmsworldgen.rust;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

public final class RustSelfTest {

    private static final long[] SEEDS = {0L, 1L, -1L, 8682522807148012L, 0x7FFFFFFFFFFFFFFFL, 4_611_686_018_427_387_904L};
    private static final double[][] BLENDED = {
        {0.25, 0.125, 80.0, 160.0, 8.0},
        {1.0, 1.0, 2.0, 3.0, 4.0},
    };
    private static final int MAX_REPORTED = 5;

    private int total;
    private int mismatches;
    private final List<String> reports = new ArrayList<>();

    private RustSelfTest() {
    }

    // Compares the Rust ports with the vanilla classes and returns a one-line result.
    public static String run() {
        RustSelfTest test = new RustSelfTest();
        test.randomChecks();
        test.noiseChecks();
        if (test.mismatches == 0) {
            String line = "Rust noise self-test passed " + test.total + "/" + test.total;
            MmsWorldgen.LOG.info(line);
            return line;
        }
        String line = "Rust noise self-test FAILED " + test.mismatches + " of " + test.total
            + " mismatches, first: " + String.join("; ", test.reports);
        MmsWorldgen.LOG.warn(line);
        return line;
    }

    private void record(String label, long expected, long actual) {
        total++;
        if (expected != actual) {
            mismatches++;
            if (reports.size() < MAX_REPORTED) {
                reports.add(label + " java=" + expected + " rust=" + actual);
            }
        }
    }

    private void fail(String label) {
        total++;
        mismatches++;
        if (reports.size() < MAX_REPORTED) {
            reports.add(label + " rust returned null");
        }
    }

    private static RandomSource source(int kind, long seed) {
        return kind == 0 ? new LegacyRandomSource(seed) : new XoroshiroRandomSource(seed);
    }

    private void randomChecks() {
        int count = 64;
        int[][] ops = {
            {0, 0}, {1, 1}, {1, 7}, {1, 256}, {1, 1000003}, {1, 0x40000000}, {2, 0}, {3, 0},
            {4, 0}, {5, 0}, {6, 0}, {7, -10}, {8, 262}, {8, 0},
        };
        for (int kind = 0; kind < 2; kind++) {
            for (long seed : SEEDS) {
                for (int[] op : ops) {
                    String label = "random kind=" + kind + " seed=" + seed + " op=" + op[0] + " arg=" + op[1];
                    long[] expected = javaRandom(kind, seed, op[0], op[1], count);
                    long[] actual = RustNative.randomSequence(kind, seed, op[0], op[1], count);
                    if (actual == null || actual.length != count) {
                        fail(label);
                        continue;
                    }
                    for (int i = 0; i < count; i++) {
                        record(label + " i=" + i, expected[i], actual[i]);
                    }
                }
            }
        }
    }

    private static long[] javaRandom(int kind, long seed, int op, int arg, int count) {
        RandomSource random = source(kind, seed);
        long[] out = new long[count];
        PositionalRandomFactory factory = null;
        if (op == 6 || op == 7) {
            factory = random.forkPositional();
        }
        if (op == 8) {
            random.consumeCount(arg);
        }
        for (int i = 0; i < count; i++) {
            out[i] = switch (op) {
                case 0 -> random.nextInt();
                case 1 -> random.nextInt(arg);
                case 2, 8 -> random.nextLong();
                case 3 -> Double.doubleToRawLongBits(random.nextDouble());
                case 4 -> Float.floatToRawIntBits(random.nextFloat()) & 0xFFFFFFFFL;
                case 5 -> Double.doubleToRawLongBits(random.nextGaussian());
                case 6 -> factory.at(i, i * 3 - 5, -i).nextLong();
                default -> factory.fromHashOf("octave_" + (arg + i)).nextLong();
            };
        }
        return out;
    }

    private static double[] points() {
        Random random = new Random(42L);
        List<double[]> list = new ArrayList<>();
        double[] fixed = {0.0, -0.0, 0.5, -0.5, 1.0, -1.0, 1.0E-9, 255.999, 256.0, -256.001, 33554432.0, 16777216.0, -16777216.5, 30_000_000.0, -30_000_000.0, 29_999_999.75};
        for (double x : fixed) {
            list.add(new double[] {x, 64.0, -x});
            list.add(new double[] {x * 0.37, x, x * 1.3});
        }
        for (int i = 0; i < 120; i++) {
            list.add(new double[] {
                random.nextDouble() * 128.0 - 64.0,
                random.nextDouble() * 384.0 - 64.0,
                random.nextDouble() * 128.0 - 64.0,
            });
        }
        for (int i = 0; i < 60; i++) {
            list.add(new double[] {
                (random.nextDouble() * 2.0 - 1.0) * 30_000_000.0,
                -64.0 + random.nextInt(385),
                (random.nextDouble() * 2.0 - 1.0) * 30_000_000.0,
            });
        }
        for (int i = 0; i < 60; i++) {
            list.add(new double[] {random.nextInt(2001) - 1000, -64 + random.nextInt(385), random.nextInt(2001) - 1000});
        }
        double[] flat = new double[list.size() * 3];
        for (int i = 0; i < list.size(); i++) {
            System.arraycopy(list.get(i), 0, flat, i * 3, 3);
        }
        return flat;
    }

    private interface Sampler {
        double sample(double x, double y, double z);
    }

    private void compare(String label, int kind, long seed, int firstOctave, double[] params, double[] points, Sampler sampler) {
        double[] actual = RustNative.sampleNoise(kind, seed, firstOctave, params, points);
        if (actual == null || actual.length != points.length / 3) {
            fail(label);
            return;
        }
        for (int i = 0; i < actual.length; i++) {
            double expected = sampler.sample(points[i * 3], points[i * 3 + 1], points[i * 3 + 2]);
            record(
                label + " at " + points[i * 3] + "," + points[i * 3 + 1] + "," + points[i * 3 + 2],
                Double.doubleToRawLongBits(expected),
                Double.doubleToRawLongBits(actual[i])
            );
        }
    }

    @SuppressWarnings("deprecation")
    private void noiseChecks() {
        double[] points = points();
        double[][] shapes = {{1.0, 1.0}, {1.0, 0.0, 2.0, 0.5}, {1.5, 0.0, 1.0, 0.0, 0.0, 0.0}, {1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0}, {1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0}};
        int[] octaves = {-7, -7, -10, -7, -9};
        for (long seed : SEEDS) {
            String tag = "seed=" + seed;
            ImprovedNoise xoro = new ImprovedNoise(new XoroshiroRandomSource(seed));
            compare("improved-xoro " + tag, 0, seed, 0, new double[0], points, xoro::noise);
            ImprovedNoise legacy = new ImprovedNoise(new LegacyRandomSource(seed));
            compare("improved-legacy " + tag, 1, seed, 0, new double[0], points, legacy::noise);
            double[][] scaled = {{0.5, 0.25}, {0.1, -1.0}, {2.0, 100.0}};
            for (double[] s : scaled) {
                ImprovedNoise n = new ImprovedNoise(new XoroshiroRandomSource(seed));
                compare("improved-scaled " + tag + " " + s[0] + "," + s[1], 2, seed, 0, s, points, (x, y, z) -> n.noise(x, y, z, s[0], s[1]));
            }

            for (int i = 0; i < shapes.length; i++) {
                double[] amps = shapes[i];
                int first = octaves[i];
                String shape = tag + " first=" + first + " amps=" + amps.length;
                PerlinNoise perlin = PerlinNoise.create(new XoroshiroRandomSource(seed), first, new DoubleArrayList(amps));
                compare("perlin " + shape, 3, seed, first, amps, points, perlin::getValue);
                NormalNoise normal = NormalNoise.create(new XoroshiroRandomSource(seed), new NormalNoise.NoiseParameters(first, new DoubleArrayList(amps)));
                compare("normal " + shape, 4, seed, first, amps, points, normal::getValue);
                PerlinNoise perlinLegacySource = PerlinNoise.create(new LegacyRandomSource(seed), first, new DoubleArrayList(amps));
                compare("perlin-legacy-source " + shape, 8, seed, first, amps, points, perlinLegacySource::getValue);
                PerlinNoise nether = PerlinNoise.createLegacyForLegacyNetherBiome(new LegacyRandomSource(seed), first, new DoubleArrayList(amps));
                compare("perlin-nether " + shape, 5, seed, first, amps, points, nether::getValue);
                NormalNoise netherNormal = NormalNoise.createLegacyNetherBiome(new LegacyRandomSource(seed), new NormalNoise.NoiseParameters(first, new DoubleArrayList(amps)));
                compare("normal-nether " + shape, 6, seed, first, amps, points, netherNormal::getValue);
            }

            double[] positive = {1.0, 1.0};
            PerlinNoise up = PerlinNoise.create(new XoroshiroRandomSource(seed), 2, new DoubleArrayList(positive));
            compare("perlin-positive " + tag, 3, seed, 2, positive, points, up::getValue);

            for (double[] params : BLENDED) {
                BlendedNoise blended = new BlendedNoise(new XoroshiroRandomSource(seed), params[0], params[1], params[2], params[3], params[4]);
                compare("blended " + tag + " xz=" + params[0], 7, seed, 0, params, points, (x, y, z) -> blended.compute(new DensityFunction.SinglePointContext((int) x, (int) y, (int) z)));
            }
        }
    }
}
