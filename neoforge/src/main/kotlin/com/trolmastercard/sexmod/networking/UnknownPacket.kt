package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.ServerWhitelistManager
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * Custom-model availability handshake (the 1.12 log calls it
 * "RequestServerModelAvailability"), registered for both directions.
 *
 * - CLIENT -> SERVER (empty): asks for the server's whitelisted model scales.
 *   The server answers with an [UnknownPacket] carrying
 *   `ServerWhitelistManager.getModelScales()`.
 * - SERVER -> CLIENT: if global rendering is restricted on this client, every
 *   model that is not disabled, or whose server scale exceeds the local
 *   z-offset limit, is requested with a [DownloadServerModelPacket].
 *
 * 1.12 wrote the entries only from a non-client proxy, so an integrated server
 * (client proxy) answered with an empty map; that is kept by answering with an
 * empty map when running on a physical client. The 1.21 wire format always
 * carries the (possibly empty) map.
 */
class UnknownPacket(@JvmField val entries: HashMap<String, Float>) : CustomPacketPayload {
    constructor() : this(HashMap())

    override fun type(): CustomPacketPayload.Type<UnknownPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeInt(entries.size)

        for ((key, value) in entries) {
            buf.writeUtf(key)
            buf.writeFloat(value)
        }
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<UnknownPacket>(Main.rl("unknown"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, UnknownPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf ->
                val map = HashMap<String, Float>()
                val count = buf.readInt()
                for (i in 0 until count) {
                    map[buf.readUtf()] = buf.readFloat()
                }
                UnknownPacket(map)
            })

        @JvmStatic
        fun handle(packet: UnknownPacket, ctx: IPayloadContext) {
            if (ctx.flow().isClientbound) {
                if (!ServerWhitelistManager.isGlobalRenderingDisabled()) {
                    return
                }

                val enabledModels = ArrayList<String>()

                for ((modelName, scale) in packet.entries) {
                    if (!ServerWhitelistManager.isModelDisabled(modelName)) {
                        enabledModels.add(modelName)
                    } else {
                        val zOffset = ServerWhitelistManager.getModelZOffset(modelName)
                        if (scale > zOffset) {
                            enabledModels.add(modelName)
                        }
                    }
                }

                // 1.12 returned the reply from the client handler, i.e. sent it to the server
                PacketHandler.networkWrapper.sendToServer(DownloadServerModelPacket(enabledModels))
            } else {
                val scales: HashMap<String, Float> =
                    if (FMLEnvironment.dist.isClient) HashMap() else ServerWhitelistManager.getModelScales()
                PacketHandler.networkWrapper.sendTo(UnknownPacket(scales), ctx.player() as ServerPlayer)
            }
        }
    }
}
