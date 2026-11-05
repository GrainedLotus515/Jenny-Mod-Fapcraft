package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.AllieEntity
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import net.neoforged.neoforge.network.handling.IPayloadContext

/**
 * <b>Role.</b> CLIENT->SERVER "summon my Allie" request from the Allies lamp's
 * first use. Spawns an [AllieEntity] two blocks in front of the player,
 * facing away from him.
 *
 * <b>Handler.</b> SERVER-side, on the main thread. Creates the Allie
 * from the player's held item, sets her as the player's interaction partner,
 * disables gravity/clip (she hovers while being summoned) and picks her summon
 * action: `SUMMON_SAND` if standing on sand, `SUMMON` if she holds a
 * lamp, otherwise `SUMMON_NORMAL`.
 */
class SummonAlliePacket() : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<SummonAlliePacket> = TYPE

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<SummonAlliePacket>(Main.rl("summon_allie"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, SummonAlliePacket> = StreamCodec.of({ _, _ -> }, { SummonAlliePacket() })

        @JvmStatic
        fun handle(packet: SummonAlliePacket, ctx: IPayloadContext) {
            val player = ctx.player() as ServerPlayer
            val spawnPos = player.position().add(
                -Math.sin(player.yHeadRot * (Math.PI / 180.0)) * 2.0,
                0.0,
                Math.cos(player.yHeadRot * (Math.PI / 180.0)) * 2.0,
            )
            val allie = AllieEntity(player.level(), player.mainHandItem)
            allie.setInteractionPlayerUUID(player.uuid)
            allie.absMoveTo(spawnPos.x, spawnPos.y, spawnPos.z, player.yHeadRot + 180.0f, player.xRot)
            allie.setTargetPosition(allie.position())
            allie.setYawRotation(player.yHeadRot + 180.0f)
            allie.setNoGravity(true)
            allie.noPhysics = true
            player.level().addFreshEntity(allie)
            val blockPos = allie.blockPosition().offset(0, -1, 0)
            if (allie.level().getBlockState(blockPos).block == Blocks.SAND) {
                allie.setCurrentAction(Action.SUMMON_SAND)
            } else {
                allie.setCurrentAction(if (allie.hasLampItem()) Action.SUMMON else Action.SUMMON_NORMAL)
            }
        }
    }
}
