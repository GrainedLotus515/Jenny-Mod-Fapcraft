package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GalathEntity
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * SERVER-bound packet: applies a velocity to a girl entity (impulses from the
 * rider while riding Galath). Only the player currently riding her may steer.
 */
class UpdateVelocityPacket(@JvmField val velocity: Vec3, @JvmField val girlUUID: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<UpdateVelocityPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeDouble(velocity.x)
        buf.writeDouble(velocity.y)
        buf.writeDouble(velocity.z)
        buf.writeUtf(girlUUID.toString())
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<UpdateVelocityPacket>(Main.rl("update_velocity"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, UpdateVelocityPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> UpdateVelocityPacket(Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()), UUID.fromString(buf.readUtf())) })

        @JvmStatic
        fun handle(packet: UpdateVelocityPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid message @UpdateVelocity :(")
                return
            }
            val girl = BaseGirlEntity.getServerGirlEntity(packet.girlUUID)
            if (girl is GalathEntity) {
                val galath = girl
                if (ctx.player() == galath.getRidingPlayer()) {
                    galath.applyVelocityDelta(packet.velocity)
                }
            }
        }
    }
}
