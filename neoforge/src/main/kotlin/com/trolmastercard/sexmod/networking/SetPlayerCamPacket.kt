package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * **Role.** SERVER->CLIENT camera snap during girl scenes — forces the
 * client's view (third-person mode, yaw, pitch) to match the scene's camera
 * setup. Sent as part of scene entry/exit so all observers see the scene from
 * the intended angle.
 *
 * **Handler.** CLIENT-side (already on the client thread in 1.21).
 * Sets the camera type ([camMode] uses the 1.12 `thirdPersonView` values,
 * mapped to [CameraType]) and writes yaw/pitch (current and previous) into
 * the local player so interpolation does not fight the snap.
 */
class SetPlayerCamPacket(@JvmField val camX: Float, @JvmField val camY: Float, @JvmField val camMode: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SetPlayerCamPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeFloat(camX)
        buf.writeFloat(camY)
        buf.writeInt(camMode)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SetPlayerCamPacket>(Main.rl("set_player_cam"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SetPlayerCamPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> SetPlayerCamPacket(buf.readFloat(), buf.readFloat(), buf.readInt()) })

        @JvmStatic
        fun handle(packet: SetPlayerCamPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isClientbound) {
                Main.LOGGER.warn("received an invalid message @SetPlayerCam :(")
                return
            }
            Main.LOGGER.info(Thread.currentThread().name)
            Client.apply(packet)
        }
    }

    /** Client-only half (§2.3 Rule C). */
    private object Client {
        fun apply(packet: SetPlayerCamPacket) {
            val minecraft = Minecraft.getInstance()
            val types = CameraType.entries
            minecraft.options.cameraType = types[Math.floorMod(packet.camMode, types.size)]
            val player = minecraft.player ?: return
            player.yRot = packet.camY
            player.yRotO = packet.camY
            player.yHeadRotO = packet.camY
            player.yHeadRot = packet.camY
            player.yBodyRot = packet.camY
            player.xRot = packet.camX
            player.xRotO = packet.camX
        }
    }
}
