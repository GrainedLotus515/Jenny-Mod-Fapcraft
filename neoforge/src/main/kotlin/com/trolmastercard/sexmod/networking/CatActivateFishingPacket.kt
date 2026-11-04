package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.LunaEntity
import com.trolmastercard.sexmod.item.LunaRodItem
import java.util.UUID
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.InteractionHand
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * <b>Role.</b> CLIENT->SERVER request to make the cat-girl Luna cast her fishing
 * rod. Sent from the interaction menu ("go fishing") after the player hands her a
 * [LunaRodItem].
 *
 * <b>Handler.</b> SERVER-side, on the main thread. Finds the
 * [LunaEntity] by UUID and calls
 * [LunaRodItem.castFishingRod] with the rod
 * she is holding — spawning the `SexEntity` bobber (SERVER side; the
 * entity spawn itself syncs to clients).
 */
class CatActivateFishingPacket(@JvmField val catUUID: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CatActivateFishingPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(catUUID.toString())
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<CatActivateFishingPacket>(Main.rl("cat_activate_fishing"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, CatActivateFishingPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> CatActivateFishingPacket(UUID.fromString(buf.readUtf())) })

        @JvmStatic
        fun handle(packet: CatActivateFishingPacket, ctx: IPayloadContext) {
            for (girl in BaseGirlEntity.girlList(packet.catUUID)) {
                if (!girl.level().isClientSide && girl is LunaEntity) {
                    val stack = girl.heldRod
                    val rodItem = stack.item as LunaRodItem
                    rodItem.castFishingRod(ctx.player().level(), girl, InteractionHand.MAIN_HAND)
                }
            }
        }
    }
}
