package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.item.KoboldEggItem
import com.trolmastercard.sexmod.util.ItemNbt
import com.trolmastercard.sexmod.util.KoboldManager
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * <b>Role.</b> CLIENT->SERVER "give me a tribe egg" request from the dragon-staff
 * UI. Gives the player a [KoboldEggItem] dyed in the tribe color and tagged
 * with the tribe UUID, so hatching it produces a kobold of *their* tribe.
 *
 * <b>Handler.</b> SERVER-side, on the main thread. No-op if the sender
 * has no tribe. The egg carries `tribeID` NBT which
 * [KoboldEggItem] reads when placing the egg entity. The 1.12 item metadata
 * (wool colour) is stored with [ItemNbt.setMeta].
 */
class SendEggPacket() : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SendEggPacket> = TYPE

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SendEggPacket>(Main.rl("send_egg"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SendEggPacket> = StreamCodec.of({ _, _ -> }, { SendEggPacket() })

        @JvmStatic
        fun handle(packet: SendEggPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid Message @SendEgg :(")
                return
            }

            val player = ctx.player() as ServerPlayer
            val tribeUuid = KoboldManager.getTribeUUID(player.uuid)
            if (tribeUuid != null) {
                val color = KoboldManager.getTribeColor(tribeUuid)
                val stack = ItemStack(KoboldEggItem.KOBOLD_EGG_ITEM, 1)
                ItemNbt.setMeta(stack, color.getWoolMeta())
                var tag = ItemNbt.getTag(stack)
                if (tag == null) {
                    tag = CompoundTag()
                }

                tag.putString("tribeID", tribeUuid.toString())
                ItemNbt.setTag(stack, tag)
                player.inventory.add(stack)
            }
        }
    }
}
