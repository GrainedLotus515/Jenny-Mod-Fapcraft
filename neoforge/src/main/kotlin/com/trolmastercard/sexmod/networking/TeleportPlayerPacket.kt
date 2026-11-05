package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

/**
 * **Role.** CLIENT->SERVER hard teleport of a player (by UUID) to a position
 * with optional yaw/pitch. Used by scene transitions and admin-style commands;
 * the client is reset via the connection's `teleport` so vanilla
 * interpolation cannot fight the teleport.
 *
 * **Handler.** SERVER-side. Wraps yaw/pitch, sets position/angles + head
 * rotation, zeroes motion and confirms the move to the player's connection.
 * Unknown UUID -> logs the available players and returns.
 */
class TeleportPlayerPacket(
    @JvmField val girlId: String,
    @JvmField val position: Vec3,
    @JvmField var yaw: Float,
    @JvmField var pitch: Float,
) : CustomPacketPayload {
    constructor(girlId: String, position: Vec3) : this(girlId, position, 0.0f, 0.0f)

    constructor(girlId: String, x: Double, y: Double, z: Double, yaw: Float, pitch: Float) : this(girlId, Vec3(x, y, z), yaw, pitch)

    override fun type(): CustomPacketPayload.Type<TeleportPlayerPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlId)
        buf.writeDouble(position.x)
        buf.writeDouble(position.y)
        buf.writeDouble(position.z)
        buf.writeFloat(yaw)
        buf.writeFloat(pitch)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<TeleportPlayerPacket>(Main.rl("teleport_player"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, TeleportPlayerPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> TeleportPlayerPacket(buf.readUtf(), Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()), buf.readFloat(), buf.readFloat()) })

        @JvmStatic
        fun handle(packet: TeleportPlayerPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid message @TeleportPlayer :(")
                return
            }
            val server = ServerLifecycleHooks.getCurrentServer() ?: return
            try {
                Main.LOGGER.info("teleporting player " + packet.girlId + " to " + packet.position)
                val player = server.playerList.getPlayer(UUID.fromString(packet.girlId))!! // java-npe: caught below, logs the player list
                packet.yaw = Mth.wrapDegrees(packet.yaw)
                packet.pitch = Mth.wrapDegrees(packet.pitch)
                player.moveTo(packet.position.x, packet.position.y, packet.position.z, packet.yaw, packet.pitch)
                player.yHeadRot = packet.yaw
                player.setDeltaMovement(0.0, 0.0, 0.0)
                player.connection.teleport(packet.position.x, packet.position.y, packet.position.z, packet.yaw, packet.pitch)
            } catch (exception: Exception) {
                Main.LOGGER.warn("couldn't find player with UUID: " + packet.girlId)
                Main.LOGGER.info("could only find the following players:")
                Main.LOGGER.info(server.playerList.players.joinToString(", ") { it.gameProfile.name + " (" + it.stringUUID + ")" })
            }
        }
    }
}
