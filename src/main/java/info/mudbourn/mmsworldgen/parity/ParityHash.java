package info.mudbourn.mmsworldgen.parity;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ParityHash {
    private static final long OFFSET = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;
    private static final Map<BlockState, Long> STATE_HASHES = new ConcurrentHashMap<>();

    private ParityHash() {
    }

    // Hashes every section of a chunk, bottom to top.
    public static long[] sections(ChunkAccess chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        long[] hashes = new long[sections.length];
        for (int i = 0; i < sections.length; i++) {
            hashes[i] = section(sections[i]);
        }
        return hashes;
    }

    private static long section(LevelChunkSection section) {
        long hash = OFFSET;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    hash = mix(hash, stateHash(section.getBlockState(x, y, z)));
                }
            }
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        for (int shift = 0; shift < 64; shift += 8) {
            hash = (hash ^ ((value >>> shift) & 0xffL)) * PRIME;
        }
        return hash;
    }

    private static long stateHash(BlockState state) {
        Long cached = STATE_HASHES.get(state);
        if (cached != null) {
            return cached;
        }
        long hash = computeStateHash(state);
        STATE_HASHES.put(state, hash);
        return hash;
    }

    private static long computeStateHash(BlockState state) {
        StringBuilder text = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        state.getProperties()
            .stream()
            .sorted((a, b) -> a.getName().compareTo(b.getName()))
            .forEach(property -> appendProperty(text, state, property));
        long hash = OFFSET;
        for (byte b : text.toString().getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (b & 0xffL)) * PRIME;
        }
        return hash;
    }

    private static void appendProperty(StringBuilder text, BlockState state, Property<?> property) {
        text.append(';')
            .append(property.getName())
            .append('=')
            .append(valueName(state, property));
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
