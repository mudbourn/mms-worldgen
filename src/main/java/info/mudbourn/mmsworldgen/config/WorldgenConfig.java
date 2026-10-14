package info.mudbourn.mmsworldgen.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import info.mudbourn.mmsworldgen.MmsWorldgen;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WorldgenConfig {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create();

    private static final String FILE_NAME = "mms_worldgen.json";

    public Map<String, Dimension> dimensions = new LinkedHashMap<>();

    public static final class Dimension {
        public int cellSize = 24;
        public double emptyChance = 0.15;
        public int noveltyRadius = 3;
        public double noveltyFalloff = 0.35;
        public int retries = 3;
        public double defaultWeight = 1.0;
        public List<String> exempt = new ArrayList<>(List.of(
            "minecraft:strongholds",
            "minecraft:ancient_cities",
            "minecraft:trial_chambers",
            "minecraft:ocean_monuments"
        ));
        public List<Group> groups = new ArrayList<>();

        private void validate(String id) {
            cellSize = fix(id, "cell_size", cellSize, 2, Integer.MAX_VALUE);
            emptyChance = fix(id, "empty_chance", emptyChance, 0, 1);
            retries = fix(id, "retries", retries, 0, Integer.MAX_VALUE);
            noveltyRadius = fix(id, "novelty_radius", noveltyRadius, 0, 8);
            noveltyFalloff = fix(id, "novelty_falloff", noveltyFalloff, 0, 1);
            defaultWeight = fix(id, "default_weight", defaultWeight, 0, Double.MAX_VALUE);
            if (exempt == null) {
                exempt = new ArrayList<>();
            }
            if (groups == null) {
                groups = new ArrayList<>();
            }
            groups.removeIf(Objects::isNull);
            for (Group group : groups) {
                group.validate(id);
            }
        }
    }

    public static final class Group {
        public String name = "";
        public List<String> sets = new ArrayList<>();
        public double weight = 1.0;
        public Map<String, Double> setWeights = new LinkedHashMap<>();

        private void validate(String dimension) {
            if (name == null) {
                name = "";
            }
            if (sets == null) {
                sets = new ArrayList<>();
            }
            if (setWeights == null) {
                setWeights = new LinkedHashMap<>();
            }
            weight = fix(dimension, name + ".weight", weight, 0, Double.MAX_VALUE);
            setWeights.replaceAll((pattern, value) -> fix(
                dimension,
                name + ".set_weights." + pattern,
                value == null ? 1.0 : value,
                0,
                Double.MAX_VALUE
            ));
        }
    }

    private static int fix(String dimension, String field, int value, int min, int max) {
        int fixed = Math.max(min, Math.min(max, value));
        if (fixed != value) {
            MmsWorldgen.LOG.warn("{} {} = {} is out of range, using {}", dimension, field, value, fixed);
        }
        return fixed;
    }

    private static double fix(String dimension, String field, double value, double min, double max) {
        double fixed = Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
        if (fixed != value) {
            MmsWorldgen.LOG.warn("{} {} = {} is out of range, using {}", dimension, field, value, fixed);
        }
        return fixed;
    }

    // Loads config from disk, writing defaults when the file is missing.
    public static WorldgenConfig load() {
        Path path = configPath();
        if (!Files.exists(path)) {
            WorldgenConfig fresh = defaults();
            fresh.save();
            return fresh;
        }

        try {
            WorldgenConfig loaded = GSON.fromJson(Files.readString(path), WorldgenConfig.class);
            if (loaded == null) {
                return defaults();
            }
            loaded.validate();
            return loaded;
        } catch (IOException | RuntimeException e) {
            MmsWorldgen.LOG.error("Failed to read {}, using defaults", FILE_NAME, e);
            return defaults();
        }
    }

    private void validate() {
        if (dimensions == null) {
            dimensions = new LinkedHashMap<>();
        }
        dimensions.forEach((id, dim) -> {
            if (dim != null) {
                dim.validate(id);
            }
        });
        dimensions.values().removeIf(Objects::isNull);
    }

    private static WorldgenConfig defaults() {
        WorldgenConfig config = new WorldgenConfig();
        Dimension overworld = new Dimension();

        Group villages = new Group();
        villages.name = "villages";
        villages.sets = new ArrayList<>(List.of("minecraft:villages", "t_and_t:*village*"));
        villages.weight = 3.0;
        overworld.groups.add(villages);

        Group taverns = new Group();
        taverns.name = "dungeons_and_taverns";
        taverns.sets = new ArrayList<>(List.of("nova_structures:*"));
        taverns.weight = 2.0;
        overworld.groups.add(taverns);

        config.dimensions.put("minecraft:overworld", overworld);
        return config;
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
