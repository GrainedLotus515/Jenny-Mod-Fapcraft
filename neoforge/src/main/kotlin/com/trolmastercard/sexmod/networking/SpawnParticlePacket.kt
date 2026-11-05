package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.util.ParticleCompat
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * **Role.** SERVER->CLIENT particle burst around a girl (used by scenes and
 * item effects, e.g. heart/heal particles).
 *
 * [particleType] is a particle registry ID (`ParticleCompat.name(ParticleTypes.X)`)
 * or a 1.12 particle name; both resolve through [ParticleCompat.byName].
 *
 * **Handler.** CLIENT-side. For the client-side entity matching the UUID,
 * spawns [count] particles of the named type around her via
 * `BaseGirlEntity.spawnParticlesAround`.
 */
class SpawnParticlePacket(
    @JvmField val girlUUID: UUID,
    @JvmField val particleType: String,
    @JvmField val count: Int,
) : CustomPacketPayload {
    constructor(girlUUID: UUID, particleType: String) : this(girlUUID, particleType, 1)

    override fun type(): CustomPacketPayload.Type<SpawnParticlePacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
        buf.writeUtf(particleType)
        buf.writeInt(count)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SpawnParticlePacket>(Main.rl("spawn_particle"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SpawnParticlePacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> SpawnParticlePacket(UUID.fromString(buf.readUtf()), buf.readUtf(), buf.readInt()) })

        @JvmStatic
        fun handle(packet: SpawnParticlePacket, ctx: IPayloadContext) {
            if (!ctx.flow().isClientbound) {
                Main.LOGGER.warn("received an invalid message @SpawnParticle :(")
                return
            }
            val particle = ParticleCompat.byName(packet.particleType)
            if (particle == null) {
                Main.LOGGER.warn("unknown particle type @SpawnParticle: " + packet.particleType)
                return
            }
            for (girl in BaseGirlEntity.girlList(packet.girlUUID)) {
                if (girl.level().isClientSide) {
                    for (i in 0 until packet.count) {
                        BaseGirlEntity.spawnParticlesAround(particle, girl)
                    }
                    break
                }
            }
        }
    }
}
