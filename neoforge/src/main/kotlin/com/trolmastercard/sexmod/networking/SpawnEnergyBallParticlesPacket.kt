package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GalathEntity
import com.trolmastercard.sexmod.item.GalathCoinItem
import java.util.UUID
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * **Role.** SERVER->CLIENT Galath summon trigger — makes the client render the
 * Galath's summon particle burst and switches the ownership debug flag.
 *
 * **Handler.** CLIENT-side. Looks up the client-side [GalathEntity] and
 * calls [GalathCoinItem.summonGalathFor] which spawns
 * the energy-ball particles; if the UUID is the local player's, ownership debug
 * is turned off so the summoned girl behaves as owned.
 *
 * Either UUID may be `null` (wire-sent as the sentinel string
 * `"trol was here"`); the handler tolerates that.
 */
class SpawnEnergyBallParticlesPacket(@JvmField val girlUUID: UUID?, @JvmField val playerUUID: UUID?) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SpawnEnergyBallParticlesPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(this.girlUUID?.toString() ?: "trol was here")
        buf.writeUtf(this.playerUUID?.toString() ?: "trol was here")
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SpawnEnergyBallParticlesPacket>(Main.rl("spawn_energy_ball_particles"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SpawnEnergyBallParticlesPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> SpawnEnergyBallParticlesPacket(readUuidOrNull(buf), readUuidOrNull(buf)) })

        private fun readUuidOrNull(buf: FriendlyByteBuf): UUID? {
            val raw = buf.readUtf()
            return try {
                UUID.fromString(raw)
            } catch (exception: Exception) {
                null
            }
        }

        @JvmStatic
        fun handle(packet: SpawnEnergyBallParticlesPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isClientbound) {
                Main.LOGGER.warn("received an invalid message @SpawnEnergyBallParticles :(")
                return
            }

            val girl = BaseGirlEntity.getClientGirlEntity(packet.girlUUID)
            if (girl !is GalathEntity) {
                Main.LOGGER.info("doesnt exit")
            } else {
                GalathCoinItem.summonGalathFor(packet.playerUUID, girl)
            }
        }
    }
}
