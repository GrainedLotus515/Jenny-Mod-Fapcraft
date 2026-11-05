package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * **Role.** CLIENT->SERVER inventory cleanup — removes one stack of the given
 * item type from the target player's inventory. Used when a girl gives/takes
 * items and the client-side inventory preview must stay in sync with the server.
 *
 * **Handler.** SERVER-side. The target is the player who sent the packet
 * (`ctx.player()`); the UUID carried on the wire is kept only for compatibility
 * and is not trusted. Shrinks the first stack in the player's main inventory
 * that is the same item (1.12: same item and metadata; after the Flattening the
 * metadata is the item identity), removing at most what the stack actually holds.
 */
class RemoveItemsPacket(@JvmField val girlUUID: UUID, @JvmField val itemStack: ItemStack) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<RemoveItemsPacket> = TYPE

    fun write(buf: RegistryFriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, itemStack)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<RemoveItemsPacket>(Main.rl("remove_items"))

        @JvmField
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RemoveItemsPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> RemoveItemsPacket(UUID.fromString(buf.readUtf()), ItemStack.OPTIONAL_STREAM_CODEC.decode(buf)) })

        @JvmStatic
        fun handle(packet: RemoveItemsPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.info("recieved an unvalid message @RemoveItems :(")
                return
            }
            val sender = ctx.player()
            val requested = packet.itemStack
            if (requested.isEmpty) {
                return
            }

            val inventory = sender.inventory

            for (stack in inventory.items) {
                if (ItemStack.isSameItem(stack, requested)) {
                    stack.shrink(Math.min(requested.count, stack.count))
                    break
                }
            }
        }
    }
}
