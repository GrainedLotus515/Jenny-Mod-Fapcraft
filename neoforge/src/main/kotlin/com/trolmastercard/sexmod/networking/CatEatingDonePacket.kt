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
 * <b>Role.</b> CLIENT->SERVER "eat/reel in" tick for Luna's fishing minigame. Sent
 * by the client while Luna is eating a caught fish, advancing her bobber state.
 *
 * <b>Handler.</b> SERVER-side, on the main thread. Calls
 * [LunaEntity.onFishingTick] on the Luna entity matching the UUID so the
 * fishing catch timer / reel-in logic runs on the server. Must only be sent while
 * her `SexEntity` bobber is active; a stray packet with no matching girl is
 * ignored.
 */
class CatEatingDonePacket(@JvmField val catUUID: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CatEatingDonePacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(catUUID.toString())
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<CatEatingDonePacket>(Main.rl("cat_eating_done"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CatEatingDonePacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> CatEatingDonePacket(UUID.fromString(buf.readUtf())) })

        @JvmStatic
        fun handle(packet: CatEatingDonePacket, ctx: IPayloadContext) {
            for (girl in BaseGirlEntity.girlList(packet.catUUID)) {
                if (!girl.level().isClientSide && girl is LunaEntity) {
                    girl.onFishingTick()
                }
            }
        }
    }
}
