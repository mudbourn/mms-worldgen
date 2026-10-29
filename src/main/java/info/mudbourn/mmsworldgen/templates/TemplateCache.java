package info.mudbourn.mmsworldgen.templates;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.datafixers.DataFixer;
import info.mudbourn.mmsworldgen.MmsWorldgen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public final class TemplateCache {

    private static final int DEFAULT_DATA_VERSION = 500;
    private static final int LOG_EVERY_MISSES = 256;

    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final AtomicLong WRITES = new AtomicLong();
    private static final AtomicLong ERRORS = new AtomicLong();
    private static final AtomicLong HIT_NANOS = new AtomicLong();
    private static final AtomicLong MISS_NANOS = new AtomicLong();
    private static final AtomicBoolean ERROR_LOGGED = new AtomicBoolean();

    private static volatile Path directory;

    private TemplateCache() {
    }

    // Loads a resource template through the disk cache, or empty when vanilla should load it.
    public static Optional<StructureTemplate> load(
        ResourceManager resources,
        Identifier file,
        Identifier id,
        DataFixer fixer,
        Function<CompoundTag, StructureTemplate> reader
    ) {
        long start = System.nanoTime();
        try {
            Optional<Resource> resource = resources.getResource(file);
            if (resource.isEmpty()) {
                return Optional.empty();
            }
            byte[] bytes;
            try (InputStream stream = resource.get().open()) {
                bytes = stream.readAllBytes();
            }
            Path path = directory().resolve(key(id, bytes) + ".nbt");
            CompoundTag cached = read(path);
            if (cached != null) {
                StructureTemplate template = reader.apply(cached);
                HITS.incrementAndGet();
                HIT_NANOS.addAndGet(System.nanoTime() - start);
                return Optional.of(template);
            }
            CompoundTag raw = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            int version = NbtUtils.getDataVersion(raw, DEFAULT_DATA_VERSION);
            CompoundTag upgraded = NbtUtils.addCurrentDataVersion(
                DataFixTypes.STRUCTURE.updateToCurrentVersion(fixer, raw, version)
            );
            StructureTemplate template = reader.apply(upgraded);
            write(path, upgraded);
            long misses = MISSES.incrementAndGet();
            MISS_NANOS.addAndGet(System.nanoTime() - start);
            if (misses % LOG_EVERY_MISSES == 0) {
                MmsWorldgen.LOG.info("Template cache: {}", summary());
            }
            return Optional.of(template);
        } catch (IOException | RuntimeException e) {
            error("loading " + id, e);
            return Optional.empty();
        }
    }

    private static Path directory() throws IOException {
        Path dir = directory;
        if (dir == null) {
            dir = FabricLoader.getInstance().getGameDir().resolve("mms_worldgen").resolve("template_cache");
            Files.createDirectories(dir);
            directory = dir;
        }
        return dir;
    }

    private static String key(Identifier id, byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(id.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Integer.toString(SharedConstants.getCurrentVersion().dataVersion().version()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(bytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CompoundTag read(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (InputStream stream = Files.newInputStream(path)) {
            return NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        } catch (IOException | RuntimeException e) {
            error("reading " + path.getFileName(), e);
            return null;
        }
    }

    private static void write(Path path, CompoundTag tag) {
        Path temp = null;
        try {
            temp = Files.createTempFile(path.getParent(), "tmp", ".part");
            NbtIo.writeCompressed(tag, temp);
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            WRITES.incrementAndGet();
        } catch (IOException | RuntimeException e) {
            error("writing " + path.getFileName(), e);
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    ERRORS.incrementAndGet();
                }
            }
        }
    }

    private static void error(String action, Exception e) {
        ERRORS.incrementAndGet();
        if (ERROR_LOGGED.compareAndSet(false, true)) {
            MmsWorldgen.LOG.warn("Template cache error {} (further errors are only counted)", action, e);
        }
    }

    private static String summary() {
        return "hits=" + HITS.get()
            + " misses=" + MISSES.get()
            + " writes=" + WRITES.get()
            + " errors=" + ERRORS.get()
            + " hit_ms=" + HIT_NANOS.get() / 1_000_000
            + " miss_ms=" + MISS_NANOS.get() / 1_000_000;
    }

    // Builds /mmswg templates with the cache counters.
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("templates").executes(context -> {
            String line = "template cache (disk_cache=" + MmsWorldgen.config.templates.diskCache + "): " + summary();
            context.getSource().sendSuccess(() -> Component.literal(line), false);
            return 1;
        });
    }
}
