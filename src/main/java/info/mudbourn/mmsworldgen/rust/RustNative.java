package info.mudbourn.mmsworldgen.rust;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class RustNative {

    private static boolean loaded;
    private static String version = "none";
    private static String failure = "not requested";
    private static String selfTest = "not run";

    private RustNative() {
    }

    // Loads the native library when any Rust mode is not off.
    public static void init() {
        String mode = MmsWorldgen.config.rust.noiseMode
            + "/" + MmsWorldgen.config.rust.surfaceMode
            + "/" + MmsWorldgen.config.rust.biomesMode;
        if (!MmsWorldgen.config.rust.anyOn()) {
            MmsWorldgen.LOG.info("Rust sidecar off");
            return;
        }

        try {
            String resource = resourcePath();
            Path file = extract(resource);
            System.load(file.toAbsolutePath().toString());
            version = version();
            loaded = true;
            MmsWorldgen.LOG.info("Rust sidecar loaded {} version {} in mode {}", resource, version, mode);
            selfTest = RustSelfTest.run();
            if (selfTest.startsWith("Rust noise self-test passed")) {
                RustCompare.init();
            } else {
                loaded = false;
                failure = "self-test failed";
                MmsWorldgen.LOG.warn("Rust sidecar disabled, using Java for this session: {}", failure);
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            failure = e.toString();
            MmsWorldgen.LOG.warn("Rust sidecar unavailable, using Java for this session: {}", failure);
        }
    }

    public static boolean loaded() {
        return loaded;
    }

    public static String nativeVersion() {
        return version;
    }

    public static String failure() {
        return failure;
    }

    public static String selfTestResult() {
        return selfTest;
    }

    private static String resourcePath() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String cpu;
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            cpu = "aarch64";
        } else if (arch.equals("amd64") || arch.equals("x86_64")) {
            cpu = "x86_64";
        } else {
            throw new UnsupportedOperationException("no native library for " + os + " " + arch);
        }
        if (os.contains("mac")) {
            return "/natives/macos-" + cpu + "/libmms_worldgen_native.dylib";
        }
        if (os.contains("linux")) {
            return "/natives/linux-" + cpu + "/libmms_worldgen_native.so";
        }
        if (os.contains("windows") && cpu.equals("x86_64")) {
            return "/natives/windows-x86_64/mms_worldgen_native.dll";
        }
        throw new UnsupportedOperationException("no native library for " + os + " " + arch);
    }

    private static Path extract(String resource) throws IOException {
        try (InputStream in = RustNative.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("missing " + resource + " in jar");
            }
            String name = resource.substring(resource.lastIndexOf('/') + 1);
            Path dir = Files.createTempDirectory("mms_worldgen_native");
            Path file = dir.resolve(name);
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            file.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return file;
        }
    }

    private static native String version();

    // Returns raw long results of one random source op, or null on bad input.
    static native long[] randomSequence(int kind, long seed, int op, int arg, int count);

    // Returns noise samples at xyz triples, or null on bad input.
    static native double[] sampleNoise(
        int kind,
        long seed,
        int firstOctave,
        double[] params,
        double[] points
    );

    // Builds a native density tree from a translated router and returns its handle.
    static long createTree(long seed, boolean legacy, DensityTranslator.Translation t) {
        return createTree(
            seed,
            legacy,
            t.nodeInts(),
            t.nodeDoubles(),
            t.noiseKeys(),
            t.noiseOctaves(),
            t.noiseAmpEnds(),
            t.noiseAmps(),
            t.blended(),
            t.splineInts(),
            t.splineConsts(),
            t.pointChildren(),
            t.pointValues(),
            t.roots()
        );
    }

    private static native long createTree(
        long seed,
        boolean legacy,
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
    );

    // Returns one router field at xyz block triples, or null on bad input.
    static native double[] evalTree(long handle, int field, int[] points);

    // Releases a native density tree.
    static native void freeTree(long handle);

    // Builds a native NOISE stage over a density tree and returns its handle.
    static native long createStage(
        long tree,
        long seed,
        boolean legacy,
        int[] settings,
        int[] flags
    );

    // Runs the NOISE stage of one chunk; returns two heightmaps then palette indices.
    static native int[] fillChunk(
        long stage,
        int chunkX,
        int chunkZ,
        int chunkMinY,
        int chunkMaxY,
        int[] beardifier
    );

    // Releases a native NOISE stage.
    static native void freeStage(long stage);

    // Builds a native surface system over a density tree and returns its handle.
    static long createSurface(
        long tree,
        long seed,
        boolean legacy,
        int seaLevel,
        SurfaceTranslator.Translation t
    ) {
        return createSurface(
            tree,
            seed,
            legacy,
            seaLevel,
            t.defaultBlock(),
            t.ruleInts(),
            t.condInts(),
            t.condDoubles(),
            t.lists(),
            t.root(),
            t.noiseKeys(),
            t.noiseOctaves(),
            t.noiseAmpEnds(),
            t.noiseAmps(),
            t.gradientNames(),
            t.biomeInts()
        );
    }

    private static native long createSurface(
        long tree,
        long seed,
        boolean legacy,
        int seaLevel,
        int defaultBlock,
        int[] ruleInts,
        int[] condInts,
        double[] condDoubles,
        int[] lists,
        int root,
        String[] noiseKeys,
        int[] noiseOctaves,
        int[] noiseAmpEnds,
        double[] noiseAmps,
        String[] gradientNames,
        int[] biomeInts
    );

    // Runs the surface step of one chunk; returns two heightmaps, changed blocks and fluid marks.
    static native int[] buildSurface(
        long surface,
        int chunkX,
        int chunkZ,
        int minY,
        int maxY,
        long biomeSeed,
        int[] maps,
        int[] blocks,
        int[] flags,
        int[] biomes
    );

    // Releases a native surface system.
    static native void freeSurface(long surface);

    // Zooms biomes at xyz block triples over a quart grid, or returns null on bad input.
    static native int[] surfaceBiomes(
        long seed,
        int chunkX,
        int chunkZ,
        int minY,
        int maxY,
        int[] grid,
        int[] points
    );

    // Answers coldEnoughToSnow for biome, x, y, z quadruples, or returns null on bad input.
    static native int[] surfaceCold(long surface, int[] queries);

    // Builds a native biome sampler over a density tree and returns its handle.
    static native long createBiomes(
        long tree,
        long[] spans,
        int[] meta,
        int[] children,
        int[] roots,
        int placeholder
    );

    // Fills the biome ids of one chunk as sections hold them; regions pick a search tree per column.
    static native int[] fillBiomes(
        long biomes,
        int chunkX,
        int chunkZ,
        int minQuartY,
        int quartCount,
        int[] regions
    );

    // Releases a native biome sampler.
    static native void freeBiomes(long biomes);

    // Samples the climate at xyz quart triples as six longs per point, or returns null on bad input.
    static native long[] biomeClimate(long biomes, int[] quarts);

    // Searches one biome tree for targets of six longs each, or returns null on bad input.
    static native int[] biomeSearch(long biomes, int tree, long[] targets);

    // Returns the smallest distance from a six long target to a leaf holding the value, or -1.
    static native long biomeDistance(long biomes, int tree, int value, long[] target);
}
