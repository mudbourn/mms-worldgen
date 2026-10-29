package info.mudbourn.mmsworldgen.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import info.mudbourn.mmsworldgen.MmsWorldgen;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WorldgenConfig {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create();

    private static final String FILE_NAME = "mms_worldgen.json";

    public Scheduling scheduling = new Scheduling();
    public Parity parity = new Parity();
    public Rust rust = new Rust();
    public Compat compat = new Compat();
    public Movement movement = new Movement();
    public Templates templates = new Templates();

    public static final class Scheduling {
        public boolean enabled = false;
        public int playerMaxGenerateRate = 40;
        public int playerMaxConcurrentGenerates = 10;
        public int minConcurrentGenerates = 10;
        public int uncappedGenerateRate = 1000;
        public int uncappedConcurrentGenerates = 128;
        public int capStartPlayers = 15;
        public int capFullPlayers = 25;
        public int playerMaxLoadRate = 120;
        public boolean headingBias = true;
        public double lookaheadSeconds = 8;
        public double prefetchConeDegrees = 35;
        public int maxPrefetchChunks = 1024;
        public double prefetchShare = 0.75;

        private void validate() {
            playerMaxGenerateRate = fix(
                "scheduling.player_max_generate_rate",
                playerMaxGenerateRate,
                1,
                10000
            );
            playerMaxConcurrentGenerates = fix(
                "scheduling.player_max_concurrent_generates",
                playerMaxConcurrentGenerates,
                1,
                1024
            );
            uncappedGenerateRate = fix(
                "scheduling.uncapped_generate_rate",
                uncappedGenerateRate,
                playerMaxGenerateRate,
                10000
            );
            uncappedConcurrentGenerates = fix(
                "scheduling.uncapped_concurrent_generates",
                uncappedConcurrentGenerates,
                playerMaxConcurrentGenerates,
                1024
            );
            minConcurrentGenerates = fix(
                "scheduling.min_concurrent_generates",
                minConcurrentGenerates,
                1,
                uncappedConcurrentGenerates
            );
            capStartPlayers = fix("scheduling.cap_start_players", capStartPlayers, 0, 1000);
            capFullPlayers = fix("scheduling.cap_full_players", capFullPlayers, capStartPlayers + 1, 1001);
            playerMaxLoadRate = fix("scheduling.player_max_load_rate", playerMaxLoadRate, 1, 10000);
            lookaheadSeconds = fix("scheduling.lookahead_seconds", lookaheadSeconds, 0, 60);
            prefetchConeDegrees = fix("scheduling.prefetch_cone_degrees", prefetchConeDegrees, 5, 90);
            maxPrefetchChunks = fix("scheduling.max_prefetch_chunks", maxPrefetchChunks, 0, 4096);
            prefetchShare = fix("scheduling.prefetch_share", prefetchShare, 0, 1);
        }
    }

    public static final class Parity {
        public String mode = "off";
        public String label = "default";

        private void validate() {
            if (!"off".equals(mode) && !"record".equals(mode) && !"check".equals(mode)) {
                MmsWorldgen.LOG.warn("parity.mode = {} is not off, record or check, using off", mode);
                mode = "off";
            }
            if (label == null || !label.matches("[A-Za-z0-9_.-]+")) {
                MmsWorldgen.LOG.warn("parity.label = {} is not a plain name, using default", label);
                label = "default";
            }
        }
    }

    public static final class Rust {
        public String noiseMode = "off";
        public String surfaceMode = "off";
        public String biomesMode = "off";

        private void validate() {
            noiseMode = checkMode("rust.noise_mode", noiseMode);
            surfaceMode = checkMode("rust.surface_mode", surfaceMode);
            biomesMode = checkMode("rust.biomes_mode", biomesMode);
        }

        private static String checkMode(String key, String mode) {
            if ("off".equals(mode) || "shadow".equals(mode) || "on".equals(mode)) {
                return mode;
            }
            MmsWorldgen.LOG.warn("{} = {} is not off, shadow or on, using off", key, mode);
            return "off";
        }

        public boolean anyOn() {
            return !"off".equals(noiseMode) || !"off".equals(surfaceMode) || !"off".equals(biomesMode);
        }
    }

    public static final class Compat {
        public boolean bwgBiomeCache = true;
        public boolean terrablenderLockfree = true;
    }

    public static final class Movement {
        public boolean rejectUnreadyChunks = true;
    }

    public static final class Templates {
        public boolean diskCache = false;
    }

    private static int fix(String field, int value, int min, int max) {
        int fixed = Math.max(min, Math.min(max, value));
        if (fixed != value) {
            MmsWorldgen.LOG.warn("{} = {} is out of range, using {}", field, value, fixed);
        }
        return fixed;
    }

    private static double fix(String field, double value, double min, double max) {
        double fixed = Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
        if (fixed != value) {
            MmsWorldgen.LOG.warn("{} = {} is out of range, using {}", field, value, fixed);
        }
        return fixed;
    }

    // Loads config from disk, writing defaults when the file is missing.
    public static WorldgenConfig load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            WorldgenConfig fresh = new WorldgenConfig();
            fresh.save();
            return fresh;
        }

        try {
            WorldgenConfig loaded = GSON.fromJson(Files.readString(path), WorldgenConfig.class);
            if (loaded == null) {
                return new WorldgenConfig();
            }
            loaded.validate();
            return loaded;
        } catch (IOException | RuntimeException e) {
            MmsWorldgen.LOG.error("Failed to read {}, using defaults", FILE_NAME, e);
            return new WorldgenConfig();
        }
    }

    private void validate() {
        if (scheduling == null) {
            scheduling = new Scheduling();
        }
        if (parity == null) {
            parity = new Parity();
        }
        if (rust == null) {
            rust = new Rust();
        }
        if (compat == null) {
            compat = new Compat();
        }
        if (movement == null) {
            movement = new Movement();
        }
        if (templates == null) {
            templates = new Templates();
        }
        scheduling.validate();
        parity.validate();
        rust.validate();
    }

    private void save() {
        try {
            Files.writeString(configPath(), GSON.toJson(this));
        } catch (IOException e) {
            MmsWorldgen.LOG.error("Failed to write {}", FILE_NAME, e);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
