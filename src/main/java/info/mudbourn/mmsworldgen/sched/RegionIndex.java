package info.mudbourn.mmsworldgen.sched;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

final class RegionIndex {
    private static final long TTL_NANOS = 1_000_000_000L;
    private static final int HEADER_BYTES = 4096;
    private static final int MAX_CACHED = 64;

    private final Path directory;
    private final Map<Long, Header> headers = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Header> eldest) {
            return size() > MAX_CACHED;
        }
    };

    RegionIndex(Path directory) {
        this.directory = directory;
    }

    // Tells whether a region file already holds data for the chunk.
    boolean contains(int chunkX, int chunkZ) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long key = (((long) regionX) << 32) | (regionZ & 0xFFFFFFFFL);
        long now = System.nanoTime();
        Header header = headers.get(key);
        if (header == null || now - header.loadedAt > TTL_NANOS) {
            header = new Header(read(regionX, regionZ), now);
            headers.put(key, header);
        }
        if (header.offsets == null) {
            return false;
        }
        return header.offsets.getInt(((chunkX & 31) + (chunkZ & 31) * 32) * 4) != 0;
    }

    private ByteBuffer read(int regionX, int regionZ) {
        Path file = directory.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    return null;
                }
            }
            buffer.flip();
            return buffer;
        } catch (IOException e) {
            return null;
        }
    }

    private record Header(ByteBuffer offsets, long loadedAt) {
    }
}
