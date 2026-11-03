package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.TeleportPlayerPacket
import java.util.UUID
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/**
 * Player placement around a girl: zeroing motion, snapping the bound player to
 * the camera spot, relative camera offsets and the "in front of the player"
 * vectors.
 *
 * Extracted verbatim from [BaseGirlEntity]; the entity keeps its methods
 * as delegates and calls `getVectorTowardPlayer` / `setYawRotation`
 * back through the girl so overrides keep dispatching.
 */
object GirlPlayerPlacement {
    @JvmStatic
    fun alignPlayerToGirl(girl: BaseGirlEntity, player: ServerPlayer, teleport: Boolean) {
        player.setDeltaMovement(0.0, 0.0, 0.0)
        if (teleport) {
            val offset = girl.getVectorTowardPlayer(0.35)
            player.teleportTo(offset.x, offset.y, offset.z)
        }
    }

    @JvmStatic
    fun snapPlayerToPosition(girl: BaseGirlEntity, playerUUID: UUID) {
        val player = girl.level().getPlayerByUUID(playerUUID)!! // java-npe: the bound player must be in the level
        player.setDeltaMovement(0.0, 0.0, 0.0)
        val offset = girl.getVectorTowardPlayer(0.35)
        player.teleportTo(offset.x, offset.y, offset.z)
        girl.setYawRotation(player.yHeadRot + 180.0f)
    }

    @JvmStatic
    fun positionPlayerRelative(girl: BaseGirlEntity, dx: Double, dy: Double, dz: Double, yaw: Float, pitch: Float) {
        val interactionUuid = girl.getInteractionPlayerUUID()
        if (interactionUuid == null) {
            Main.LOGGER.info("couldnt move camera because the player isn't set")
        } else {
            val player = girl.level().getPlayerByUUID(interactionUuid)!! // java-npe: the bound player must be in the level
            if (girl.cameraOriginPos == null) {
                girl.cameraOriginPos = player.position()
            }

            var newPos: Vec3 = girl.cameraOriginPos!!
            newPos = newPos.add(
                -Math.sin((girl.cameraYaw + 90.0f) * (Math.PI / 180.0)) * dx,
                0.0,
                Math.cos((girl.cameraYaw + 90.0f) * (Math.PI / 180.0)) * dx,
            )
            newPos = newPos.add(0.0, dy, 0.0)
            newPos = newPos.add(-Math.sin(girl.cameraYaw * (Math.PI / 180.0)) * dz, 0.0, Math.cos(girl.cameraYaw * (Math.PI / 180.0)) * dz)
            if (girl.level().isClientSide) {
                PacketHandler.networkWrapper.sendToServer(TeleportPlayerPacket(player.uuid.toString(), newPos, girl.cameraYaw + yaw, pitch))
            } else {
                player.absMoveTo(newPos.x, newPos.y, newPos.z, girl.cameraYaw + yaw, pitch)
                player.teleportTo(newPos.x, newPos.y, newPos.z)
                girl.setDeltaMovement(0.0, 0.0, 0.0)
            }
        }
    }

    @JvmStatic
    fun getFrontOffsetVector(girl: BaseGirlEntity): Vec3 {
        return girl.getVectorTowardPlayer(1.0)
    }

    /** @return a point `distance` blocks in front of the interacting player's yaw */
    @JvmStatic
    fun getVectorTowardPlayer(girl: BaseGirlEntity, distance: Double): Vec3 {
        val uuid = girl.getInteractionPlayerUUID()
        val player = if (uuid == null) null else girl.level().getPlayerByUUID(uuid)
        if (player == null) {
            return girl.position()
        }

        val yaw = player.yRot
        return player.position().add(-Math.sin(yaw * (Math.PI / 180.0)) * distance, 0.0, Math.cos(yaw * (Math.PI / 180.0)) * distance)
    }

    /** CLIENT: one block from the local player toward the girl. */
    @JvmStatic
    fun getVectorTowardPlayer(girl: BaseGirlEntity): Vec3 {
        val girlPos = girl.position()
        val player = ClientHooks.localPlayer() ?: return girlPos

        val playerPos = player.position()
        val dir = girlPos.subtract(playerPos).normalize()
        return playerPos.add(dir)
    }
}
