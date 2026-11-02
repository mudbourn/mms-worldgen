package info.mudbourn.mmsworldgen.move;

import info.mudbourn.mmsworldgen.MmsWorldgen;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.TeleportTransition;

public final class TeleportPreload {
    private static final long TICKET_TICKS = 300;
    private static final int TICKET_RADIUS = 2;
    private static final int READY_RADIUS = 1;
    private static final long MAX_WAIT_TICKS = 200;

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static TicketType ticketType;
    private static boolean releasing;
    private static long tick;

    private record Pending(ServerPlayer player, TeleportTransition transition, ChunkPos target, long since) {
    }

    private TeleportPreload() {
    }

    // Registers the ticket type and the per-server-tick release check.
    public static void init() {
        ticketType = Registry.register(
            BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath("mms_worldgen", "teleport"),
            new TicketType(TICKET_TICKS, TicketType.FLAG_LOADING)
        );
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PENDING.clear());
        ServerTickEvents.END_SERVER_TICK.register(TeleportPreload::tick);
    }

    // Reports whether teleports wait for their destination chunks.
    public static boolean enabled() {
        return MmsWorldgen.config != null && MmsWorldgen.config.movement.preloadTeleports;
    }

    // Holds a same-level teleport into unloaded chunks, returning true when it was held.
    public static boolean defer(ServerPlayer player, TeleportTransition transition) {
        if (releasing || !enabled() || transition.asPassenger()) {
            return false;
        }
        if (transition.relatives().contains(Relative.X) || transition.relatives().contains(Relative.Z)) {
            return false;
        }
        ServerLevel level = transition.newLevel();
        if (level != player.level()) {
            return false;
        }
        ChunkPos target = new ChunkPos(
            Mth.floor(transition.position().x) >> 4,
            Mth.floor(transition.position().z) >> 4
        );
        if (ready(level.getChunkSource(), target)) {
            PENDING.remove(player.getUUID());
            return false;
        }
        level.getChunkSource().addTicketWithRadius(ticketType, target, TICKET_RADIUS);
        PENDING.put(player.getUUID(), new Pending(player, transition, target, tick));
        return true;
    }

    private static void tick(MinecraftServer server) {
        tick++;
        if (PENDING.isEmpty()) {
            return;
        }
        Iterator<Pending> iterator = PENDING.values().iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            ServerPlayer player = pending.player();
            if (player.isRemoved() || server.getPlayerList().getPlayer(player.getUUID()) != player) {
                iterator.remove();
                continue;
            }
            if (player.level() != pending.transition().newLevel()) {
                iterator.remove();
                continue;
            }
            boolean timedOut = tick - pending.since() >= MAX_WAIT_TICKS;
            if (timedOut || ready(player.level().getChunkSource(), pending.target())) {
                iterator.remove();
                releasing = true;
                try {
                    player.teleport(pending.transition());
                } finally {
                    releasing = false;
                }
            }
        }
    }

    private static boolean ready(ServerChunkCache cache, ChunkPos target) {
        for (int dx = -READY_RADIUS; dx <= READY_RADIUS; dx++) {
            for (int dz = -READY_RADIUS; dz <= READY_RADIUS; dz++) {
                if (cache.getChunkNow(target.x + dx, target.z + dz) == null) {
                    return false;
                }
            }
        }
        return true;
    }
}
