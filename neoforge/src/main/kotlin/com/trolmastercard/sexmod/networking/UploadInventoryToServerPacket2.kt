package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * **Role.** CLIENT->SERVER "close the girl's inventory" — removes the girl
 * entity from the server world when the player closes the chest GUI while it
 * contains the girl's items (the girl is only "materialized" while her chest is
 * open).
 *
 * **Handler.** SERVER-side. Removes every entity matching the UUID
 * (1.12 `world.removeEntity`, now `discard()`) — the client mirrors the despawn
 * via the vanilla remove-entities packet.
 */
class UploadInventoryToServerPacket2(@JvmField val girlUUID: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<UploadInventoryToServerPacket2> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<UploadInventoryToServerPacket2>(Main.rl("upload_inventory_to_server2"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, UploadInventoryToServerPacket2> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> UploadInventoryToServerPacket2(UUID.fromString(buf.readUtf())) })

        @JvmStatic
        fun handle(packet: UploadInventoryToServerPacket2, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid message @UploadInventoryToServer :(")
                return
            }
            for (girl in BaseGirlEntity.girlList(packet.girlUUID)) {
                if (!girl.level().isClientSide) {
                    girl.discard()
                }
            }
        }
    }
}
