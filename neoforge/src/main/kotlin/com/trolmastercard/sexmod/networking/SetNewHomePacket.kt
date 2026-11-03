package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * **Role.** CLIENT->SERVER "set this girl's home" command from the dragon-staff
 * UI. Sets the girl's respawn point (`homePos`) — the y coordinate is
 * floored to the block grid.
 *
 * **Handler.** SERVER-side; applies to all entities matching the girl UUID in
 * `BaseGirlEntity.girlList`. Used by `goHome()`, [SendCompanionHomePacket] and
 * general girl AI.
 */
class SetNewHomePacket(@JvmField val girlUUID: UUID, @JvmField val homePos: Vec3) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SetNewHomePacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
        buf.writeDouble(homePos.x)
        buf.writeDouble(homePos.y)
        buf.writeDouble(homePos.z)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SetNewHomePacket>(Main.rl("set_new_home"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SetNewHomePacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> SetNewHomePacket(UUID.fromString(buf.readUtf()), Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())) })

        @JvmStatic
        fun handle(packet: SetNewHomePacket, ctx: IPayloadContext) {
            val girls = BaseGirlEntity.girlList(packet.girlUUID)
            if (!girls.isEmpty()) {
                for (girl in girls) {
                    girl.homePos = Vec3(packet.homePos.x, Math.floor(packet.homePos.y), packet.homePos.z)
                }
            }
        }
    }
}
