package info.mudbourn.mmsworldgen.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import info.mudbourn.mmsworldgen.move.MoveGuard;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class MovePlayerChunkGuardMixin {

    @Shadow
    public ServerPlayer player;

    @Shadow
    public abstract void teleport(double x, double y, double z, float yRot, float xRot);

    @Inject(
        method = "handleMovePlayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"
        ),
        cancellable = true
    )
    private void mmswg$guardPlayerMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!MoveGuard.enabled()) {
            return;
        }
        double x = packet.getX(player.getX());
        double y = packet.getY(player.getY());
        double z = packet.getZ(player.getZ());
        if (MoveGuard.touchesUnready(player.level(), player, x, y, z)) {
            teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
            ci.cancel();
        }
    }

    @Inject(
        method = "handleMoveVehicle",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"
        ),
        cancellable = true
    )
    private void mmswg$guardVehicleMove(
        ServerboundMoveVehiclePacket packet,
        CallbackInfo ci,
        @Local Entity vehicle
    ) {
        if (!MoveGuard.enabled()) {
            return;
        }
        if (MoveGuard.touchesUnready(
            player.level(),
            vehicle,
            packet.position().x(),
            packet.position().y(),
            packet.position().z()
        )) {
            player.connection.send(ClientboundMoveVehiclePacket.fromEntity(vehicle));
            ci.cancel();
        }
    }
}
