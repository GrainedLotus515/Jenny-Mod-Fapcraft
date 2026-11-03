package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.AbstractGirlNpcEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * SERVER-bound packet: updates a girl's equipment/held items (used by the
 * girl inventory screens). See GirlInventoryContainer* for the UI side.
 * Never registered in 1.12; registered in 1.21 so it can actually be sent.
 */
class UpdateEquipmentPacket(@JvmField val girlUUID: UUID, @JvmField val equipmentNbt: CompoundTag) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<UpdateEquipmentPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
        buf.writeNbt(equipmentNbt)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<UpdateEquipmentPacket>(Main.rl("update_equipment"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, UpdateEquipmentPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> UpdateEquipmentPacket(UUID.fromString(buf.readUtf()), buf.readNbt() ?: CompoundTag()) })

        @JvmStatic
        fun handle(packet: UpdateEquipmentPacket, ctx: IPayloadContext) {
            for (girl in BaseGirlEntity.girlList(packet.girlUUID)) {
                if (girl is AbstractGirlNpcEntity) {
                    girl.inventory.deserializeNBT(girl.registryAccess(), packet.equipmentNbt)
                }
            }
        }
    }
}
