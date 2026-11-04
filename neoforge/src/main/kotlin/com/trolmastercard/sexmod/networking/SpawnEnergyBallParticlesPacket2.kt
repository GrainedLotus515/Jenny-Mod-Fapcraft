package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.DragonEntity
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * **Role.** SERVER->CLIENT dragon breath-effect trigger for
 * [DragonEntity] — spawns a targeted or randomized dragon-breath particle
 * burst at a world position.
 *
 * **Handler.** CLIENT-side. `isValid=true` ->
 * [DragonEntity.spawnDragonBreath], otherwise
 * [DragonEntity.spawnDragonBreathRandom]. (The 1.12 `isLeftSide` field was
 * only the "decoded" marker and is dropped.)
 */
class SpawnEnergyBallParticlesPacket2(@JvmField val energyPos: Vec3, @JvmField val isValid: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SpawnEnergyBallParticlesPacket2> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeDouble(this.energyPos.x)
        buf.writeDouble(this.energyPos.y)
        buf.writeDouble(this.energyPos.z)
        buf.writeBoolean(this.isValid)
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SpawnEnergyBallParticlesPacket2>(Main.rl("spawn_energy_ball_particles2"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SpawnEnergyBallParticlesPacket2> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> SpawnEnergyBallParticlesPacket2(Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()), buf.readBoolean()) })

        @JvmStatic
        fun handle(packet: SpawnEnergyBallParticlesPacket2, ctx: IPayloadContext) {
            if (!ctx.flow().isClientbound) {
                Main.LOGGER.warn("received an invalid message @SpawnEnergyBallParticles :(")
                return
            }

            if (packet.isValid) {
                DragonEntity.spawnDragonBreath(packet.energyPos)
            } else {
                DragonEntity.spawnDragonBreathRandom(packet.energyPos)
            }
        }
    }
}
