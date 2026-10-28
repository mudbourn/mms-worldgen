package info.mudbourn.mmsworldgen.router;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;

public final class RouterDump {
    public record Field(String name, Function<NoiseRouter, DensityFunction> getter) {
    }

    public static final List<Field> FIELDS = List.of(
        new Field("barrier", NoiseRouter::barrierNoise),
        new Field("fluid_level_floodedness", NoiseRouter::fluidLevelFloodednessNoise),
        new Field("fluid_level_spread", NoiseRouter::fluidLevelSpreadNoise),
        new Field("lava", NoiseRouter::lavaNoise),
        new Field("temperature", NoiseRouter::temperature),
        new Field("vegetation", NoiseRouter::vegetation),
        new Field("continents", NoiseRouter::continents),
        new Field("erosion", NoiseRouter::erosion),
        new Field("depth", NoiseRouter::depth),
        new Field("ridges", NoiseRouter::ridges),
        new Field("preliminary_surface_level", NoiseRouter::preliminarySurfaceLevel),
        new Field("final_density", NoiseRouter::finalDensity),
        new Field("vein_toggle", NoiseRouter::veinToggle),
        new Field("vein_ridged", NoiseRouter::veinRidged),
        new Field("vein_gap", NoiseRouter::veinGap)
    );

    private RouterDump() {
    }

    // Writes the settings and resolved noise router inventories of a level and returns the file.
    public static Path write(ServerLevel level) throws IOException {
        String dimension = level.dimension().identifier().toString();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        List<String> lines = new ArrayList<>();
        lines.add("dimension " + dimension);
        lines.add("generator " + generator.getClass().getName());
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            inventory("settings", noise.generatorSettings().value().noiseRouter(), lines);
        }
        inventory("resolved", level.getChunkSource().randomState().router(), lines);
        Path dir = FabricLoader.getInstance().getGameDir().resolve("mms_router");
        Files.createDirectories(dir);
        Path file = dir.resolve(dimension.replace(':', '_') + ".txt");
        Files.write(file, lines);
        return file;
    }

    private static void inventory(String source, NoiseRouter router, List<String> lines) {
        Map<String, Integer> types = new TreeMap<>();
        Map<String, Integer> noises = new TreeMap<>();
        Map<String, TreeSet<String>> fieldsByType = new TreeMap<>();
        for (Field field : FIELDS) {
            Map<String, Integer> fieldTypes = new TreeMap<>();
            field.getter().apply(router).mapAll(new DensityFunction.Visitor() {
                @Override
                public DensityFunction apply(DensityFunction function) {
                    String type = function.getClass().getName();
                    fieldTypes.merge(type, 1, Integer::sum);
                    fieldsByType.computeIfAbsent(type, key -> new TreeSet<>()).add(field.name());
                    return function;
                }

                @Override
                public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
                    String key = noise.noiseData()
                        .unwrapKey()
                        .map(id -> id.identifier().toString())
                        .orElse("inline");
                    noises.merge(key, 1, Integer::sum);
                    return noise;
                }
            });
            fieldTypes.forEach((type, count) -> types.merge(type, count, Integer::sum));
            int nodes = fieldTypes.values().stream().mapToInt(Integer::intValue).sum();
            lines.add(source + " field " + field.name() + " nodes=" + nodes);
        }
        types.forEach((type, count) -> {
            String fields = String.join(",", fieldsByType.get(type));
            lines.add(source + " type " + count + " " + type + " in " + fields);
        });
        noises.forEach((key, count) -> lines.add(source + " noise " + count + " " + key));
    }
}
