package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.AbstractGirlNpcEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BeeEntityBase
import com.trolmastercard.sexmod.entity.LunaEntity
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * **Role.** CLIENT->SERVER inventory upload from the girl's chest GUI — writes
 * the 36 player-inventory slots plus the girl's armor slots back onto the server.
 *
 * **Handler.** SERVER-side. Slots 0-35 go into the interacting player's
 * inventory; slot 36+ into the girl's inventory (7 slots for Luna, 6 for other
 * [AbstractGirlNpcEntity]s, 27 for [BeeEntityBase] — the target layout depends
 * on the girl type).
 *
 * **Pitfall.** The payload layout must match what the client GUI sent; a
 * length mismatch throws [ArrayIndexOutOfBoundsException] on the server.
 * The [girlUUID] resolves the girl, [playerUUID] the inventory owner.
 */
class UploadInventoryToServerPacket(
    @JvmField val girlUUID: UUID,
    @JvmField val playerUUID: UUID,
    @JvmField val inventoryItems: Array<ItemStack>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<UploadInventoryToServerPacket> = TYPE

    fun write(buf: RegistryFriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
        buf.writeUtf(playerUUID.toString())
        buf.writeInt(inventoryItems.size)

        for (stack in inventoryItems) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack)
        }
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<UploadInventoryToServerPacket>(Main.rl("upload_inventory_to_server"))

        @JvmField
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, UploadInventoryToServerPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf ->
                val girlUUID = UUID.fromString(buf.readUtf())
                val playerUUID = UUID.fromString(buf.readUtf())
                val count = buf.readInt()
                val items = Array(count) { ItemStack.OPTIONAL_STREAM_CODEC.decode(buf) }
                UploadInventoryToServerPacket(girlUUID, playerUUID, items)
            })

        @JvmStatic
        fun handle(packet: UploadInventoryToServerPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid message @UploadInventoryToServer :(")
                return
            }
            for (girl in BaseGirlEntity.girlList(packet.girlUUID)) {
                if (!girl.level().isClientSide) {
                    val player = girl.level().getPlayerByUUID(packet.playerUUID) ?: return

                    val inventory = player.inventory

                    // bounds-guard: a truncated/short payload must not index OOB
                    if (packet.inventoryItems.size < 36) {
                        return
                    }

                    for (i in 0 until 36) {
                        inventory.setItem(i, packet.inventoryItems[i])
                    }

                    if (girl is LunaEntity) {
                        val npcGirl = girl as AbstractGirlNpcEntity
                        npcGirl.inventory.setStackInSlot(0, packet.inventoryItems[36])
                        npcGirl.inventory.setStackInSlot(1, packet.inventoryItems[37])
                        npcGirl.inventory.setStackInSlot(2, packet.inventoryItems[38])
                        npcGirl.inventory.setStackInSlot(3, packet.inventoryItems[39])
                        npcGirl.inventory.setStackInSlot(4, packet.inventoryItems[40])
                        npcGirl.inventory.setStackInSlot(5, packet.inventoryItems[41])
                        npcGirl.inventory.setStackInSlot(6, packet.inventoryItems[42])
                    } else if (girl is AbstractGirlNpcEntity) {
                        val npcGirl2 = girl
                        npcGirl2.inventory.setStackInSlot(0, packet.inventoryItems[36])
                        npcGirl2.inventory.setStackInSlot(1, packet.inventoryItems[37])
                        npcGirl2.inventory.setStackInSlot(2, packet.inventoryItems[38])
                        npcGirl2.inventory.setStackInSlot(3, packet.inventoryItems[39])
                        npcGirl2.inventory.setStackInSlot(4, packet.inventoryItems[40])
                        npcGirl2.inventory.setStackInSlot(5, packet.inventoryItems[41])
                    }

                    if (girl is BeeEntityBase) {
                        val beeGirl = girl

                        for (i2 in 0 until 27) {
                            beeGirl.inventory.setStackInSlot(i2, packet.inventoryItems[i2 + 36])
                        }
                    }
                }
            }
        }
    }
}
