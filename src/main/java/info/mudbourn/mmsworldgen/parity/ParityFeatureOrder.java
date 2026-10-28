package info.mudbourn.mmsworldgen.parity;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import info.mudbourn.mmsworldgen.mixin.ParityChunkGeneratorAccessor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

// Per-dimension placed feature order, whose indices seed every decoration.
final class ParityFeatureOrder {

    private ParityFeatureOrder() {
    }

    static String fileName(String dimension) {
        return dimension + ".feature_order.txt";
    }

    // Lists each step's placed features as "step index id", in seed index order.
    static List<String> lines(ServerLevel level) {
        Registry<PlacedFeature> registry = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
        List<FeatureSorter.StepFeatureData> steps = ((ParityChunkGeneratorAccessor) level.getChunkSource()
            .getGenerator()).mmswg$featuresPerStep().get();
        List<String> lines = new ArrayList<>();
        for (int step = 0; step < steps.size(); step++) {
            List<PlacedFeature> features = steps.get(step).features();
            for (int index = 0; index < features.size(); index++) {
                Identifier id = registry.getKey(features.get(index));
                lines.add(step + " " + index + " " + (id == null ? "inline" : id.toString()));
            }
        }
        return lines;
    }

    // Writes the order on record, or compares it with the recorded one on check.
    static void handle(ServerLevel level, String dimension, Path dir, boolean recording) {
        Path file = dir.resolve(fileName(dimension));
        try {
            List<String> current = lines(level);
            if (recording) {
                Files.createDirectories(dir);
                Files.write(file, current, StandardCharsets.UTF_8);
                return;
            }
            if (!Files.exists(file)) {
                MmsWorldgen.LOG.warn("Parity feature order not recorded for {}", dimension);
                return;
            }
            List<String> recorded = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < Math.max(recorded.size(), current.size()); i++) {
                String expected = i < recorded.size() ? recorded.get(i) : "none";
                String actual = i < current.size() ? current.get(i) : "none";
                if (!expected.equals(actual)) {
                    MmsWorldgen.LOG.warn(
                        "Parity feature order differs dimension={} line={} recorded=\"{}\" current=\"{}\"",
                        dimension,
                        i,
                        expected,
                        actual
                    );
                    return;
                }
            }
            MmsWorldgen.LOG.info("Parity feature order matches for {}, {} features", dimension, current.size());
        } catch (IOException | RuntimeException e) {
            MmsWorldgen.LOG.error("Parity feature order failed for {}", dimension, e);
        }
    }
}
