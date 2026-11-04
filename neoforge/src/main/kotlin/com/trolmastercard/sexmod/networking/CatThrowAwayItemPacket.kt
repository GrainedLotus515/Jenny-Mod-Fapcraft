package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.LunaEntity
import java.util.UUID
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * <b>Role.</b> CLIENT->SERVER request to make the cat-girl Luna drop whatever she
 * is holding (e.g. a caught fish she is chewing on).
 *
 * <b>Handler.</b> SERVER-side, on the main thread. Calls
 * [LunaEntity.dropHeldItem] on the matching [LunaEntity] — the
 * held item becomes a world `ItemEntity` on the server.
 */
class CatThrowAwayItemPacket(@JvmField val catUUID: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CatThrowAwayItemPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(catUUID.toString())
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<CatThrowAwayItemPacket>(Main.rl("cat_throw_away_item"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CatThrowAwayItemPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> CatThrowAwayItemPacket(UUID.fromString(buf.readUtf())) })

        @JvmStatic
        fun handle(packet: CatThrowAwayItemPacket, ctx: IPayloadContext) {
            for (girl in BaseGirlEntity.girlList(packet.catUUID)) {
                if (!girl.level().isClientSide && girl is LunaEntity) {
                    girl.dropHeldItem()
                }
            }
        }
    }
}
