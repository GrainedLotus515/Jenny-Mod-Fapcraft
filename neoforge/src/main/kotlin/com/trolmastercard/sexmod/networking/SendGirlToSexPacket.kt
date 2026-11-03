package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.util.IBeddableSexGirl
import com.trolmastercard.sexmod.util.SceneDebug
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * CLIENT -> SERVER: asks a beddable girl ([IBeddableSexGirl]) to walk to
 * the nearest bed and begin her bed scene.
 *
 * Used by the `anal`/`doggy` choices (Bia) and the `sex`
 * choice (Luna): after the girl's `startActionFromHandState()` picks the hand-state, the client
 * sends this packet, and the SERVER handler calls
 * [IBeddableSexGirl.goToSexBed] which finds a bed, sets
 * `TARGET_POS`/`YAW_ROTATION`, and starts the walk; on arrival the
 * girl anchors and enters `WAIT_CAT`/`ANAL_WAIT` to pick up the scene.
 *
 * **Pitfall:** Luna's `startActionFromHandState()` "sex" case sends this packet together
 * with a full [ResetGirlPacket] (jar-faithful) — the reset clears the
 * interaction partner before the walk, and the girl re-binds it on arrival.
 * Do not reorder.
 */
class SendGirlToSexPacket(@JvmField val girlUUID: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SendGirlToSexPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SendGirlToSexPacket>(Main.rl("send_girl_to_sex"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SendGirlToSexPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> SendGirlToSexPacket(UUID.fromString(buf.readUtf())) })

        @JvmStatic
        fun handle(packet: SendGirlToSexPacket, ctx: IPayloadContext) {
            for (girl in BaseGirlEntity.girlList(packet.girlUUID)) {
                if (!girl.level().isClientSide && girl is IBeddableSexGirl) {
                    SceneDebug.log(SceneDebug.PACKETS, "SendGirlToSexPacket: %s -> goToSexBed (action=%s anchored=%s)", girl.getDisplayNameText(), girl.getCurrentAction(), girl.isAnchored())
                    (girl as IBeddableSexGirl).goToSexBed()
                }
            }
        }
    }
}
