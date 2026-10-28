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

    private RustNative() {
    }

    // Loads the native library when rust.mode is not off.
    public static void init() {
        String mode = MmsWorldgen.config.rust.mode;
        if ("off".equals(mode)) {
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

    private static String resourcePath() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (os.contains("mac") && arm) {
            return "/natives/macos-aarch64/libmms_worldgen_native.dylib";
        }
        if (os.contains("linux") && x64) {
            return "/natives/linux-x86_64/libmms_worldgen_native.so";
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
}
