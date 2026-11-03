package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.KoboldEggProjectileEntity
import com.trolmastercard.sexmod.util.Reference
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * **Role.** CLIENT->SERVER "send my girl home" command from the interaction
 * menu. Sends the girl home via an ender-pearl-style teleport.
 *
 * **Handler.** SERVER-side — a 3-phase state machine on the girl:
 * 1. `Action != THROW_PEARL`: start phase — set `THROW_PEARL`, face her
 *    `homePos`, anchor her.
 * 2. no active pearl yet: spawn the [KoboldEggProjectileEntity] aimed at `homePos`.
 * 3. pearl exists: burst PORTAL particles, snap her to `homePos`,
 *    un-anchor, `Action.NULL` and `goHome()`.
 *
 * Re-sending the packet advances the machine; the girl must be a bed-scene
 * capable girl (`IBeddableSexGirl` style flow) for a clean home return.
 */
class SendCompanionHomePacket(@JvmField val girlUUID: UUID) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SendCompanionHomePacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SendCompanionHomePacket>(Main.rl("send_companion_home"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SendCompanionHomePacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> SendCompanionHomePacket(UUID.fromString(buf.readUtf())) })

        @JvmStatic
        fun handle(packet: SendCompanionHomePacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid message @SendCompanionHome :(")
                return
            }
            for (girl in BaseGirlEntity.girlList(packet.girlUUID)) {
                if (!girl.level().isClientSide) {
                    val homePos = girl.homePos
                    if (girl.getCurrentAction() != Action.THROW_PEARL) {
                        girl.setCurrentAction(Action.THROW_PEARL)
                        girl.setYawRotation(
                            Math.atan2(girl.z - homePos.z, girl.x - homePos.x).toFloat() * (180.0 / Math.PI).toFloat() + 90.0f
                        )
                        girl.setTargetPosition(girl.position())
                        girl.getEntityData().set(BaseGirlEntity.IS_ANCHORED, true)
                        girl.activeEnderPearl = null
                    } else if (girl.activeEnderPearl == null) {
                        val distance = girl.position().distanceTo(homePos).toFloat()
                        val pearl = KoboldEggProjectileEntity(girl.level(), girl)
                        girl.activeEnderPearl = pearl
                        pearl.shoot(
                            homePos.x - girl.x,
                            homePos.y - girl.y,
                            homePos.z - girl.z,
                            Math.min(4.0f, distance * 0.1f),
                            0.0f
                        )
                        girl.level().addFreshEntity(pearl)
                    } else {
                        val worldServer = girl.level() as ServerLevel

                        for (i in 0 until 32) {
                            worldServer.sendParticles(
                                ParticleTypes.PORTAL,
                                girl.x,
                                girl.y + Reference.RANDOM.nextDouble() * 2.0,
                                girl.z,
                                32,
                                0.2,
                                0.2,
                                0.2,
                                Reference.RANDOM.nextGaussian()
                            )
                        }

                        girl.setPos(homePos.x, homePos.y, homePos.z)
                        girl.activeEnderPearl = null
                        girl.setCurrentAction(Action.NULL)
                        girl.getEntityData().set(BaseGirlEntity.IS_ANCHORED, false)
                        girl.goHome()
                    }
                }
            }
        }
    }
}
