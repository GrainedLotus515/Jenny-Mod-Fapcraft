package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.util.SceneDebug
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

/**
 * Scene-end packet, CLIENT -> SERVER.
 *
 * **The [resetPose] flag is INVERTED relative to intuition**
 * (jar-verified against the original bytecode):
 * - **single-arg ctor** `ResetGirlPacket(uuid)` = resetPose
 *   `false` = **FULL scene-end reset**: restores the interacting
 *   player's physics ([Handler.resetGirls]) AND releases the girl
 *   ([Handler.resetGirl] — re-adds AI tasks, un-anchors, clears the
 *   interaction partner, restores gravity/noClip, teleports her to air).
 * - **two-arg ctor** `ResetGirlPacket(uuid, true)` = resetPose
 *   `true` = **player-only reset** used by strip/doggy
 *   transitions where the girl keeps her pose.
 *
 * The natural scene end sends the single-arg form: the cum animation's
 * `xxx_cumDone` sound keyframe calls `BaseGirlEntity.resetCameraAndPhysics()` ->
 * `resetLocalPlayerClientState()` which sends this packet with the
 * girl's UUID. The R-Shift keybind (`SexSceneKeyHandler`) sends it too.
 *
 * **Pitfall:** this boolean was once inverted in the remap — the full
 * reset only ran on `true`, so every natural scene end left the girl
 * anchored/noGravity/still-interacting. Never "fix" the branch condition
 * without re-verifying against the original jar.
 */
class ResetGirlPacket(@JvmField val girlUUID: UUID, @JvmField val resetPose: Boolean) : CustomPacketPayload {
    constructor(girlUUID: UUID) : this(girlUUID, false)

    override fun type(): CustomPacketPayload.Type<ResetGirlPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
        buf.writeBoolean(resetPose)
    }

    object Handler {
        @JvmStatic
        fun resetGirl(girl: BaseGirlEntity) {
            SceneDebug.log(SceneDebug.RESET, "ResetGirlPacket.resetGirl %s remote=%s anchored=%s action=%s interact=%s", girl.getDisplayNameText(), girl.level().isClientSide, girl.isAnchored(), girl.getCurrentAction(), girl.getInteractionPlayerUUID())
            girl.reinitTasks()
            val ownerUUID: UUID? = if (girl is AbstractPlayerGirlEntity) girl.getOwnerUserUUID() else null
            if (girl is AbstractPlayerGirlEntity && ownerUUID != null && girl.level().getPlayerByUUID(ownerUUID) != null) {
                val server = ServerLifecycleHooks.getCurrentServer()
                val ownerMp = server?.getLevel(girl.level().dimension())?.getPlayerByUUID(ownerUUID) as ServerPlayer?
                if (ownerMp != null) {
                    PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(true), ownerMp)
                }
                girl.getEntityData().set(BaseGirlEntity.OUTFIT_INDEX, 1)
                val owner = girl.level().getPlayerByUUID(ownerUUID)!! // java-npe: checked non-null above
                owner.abilities.flying = false
                owner.isNoGravity = false
                owner.noPhysics = false
                girl.setAnchored(false)
                girl.setCurrentAction(Action.NULL)

                // Jar-faithful: the interacting player's physics are restored here,
                // inside the APGE branch only. For NPC girl scenes the player is
                // restored by resetGirls() in handle, which runs whenever the
                // girl has an interaction partner (before the resetPose check).
                val interactionUUID = girl.getInteractionPlayerUUID()
                if (interactionUUID != null) {
                    val interactionPlayer = girl.level().getPlayerByUUID(interactionUUID)
                    if (interactionPlayer != null) {
                        interactionPlayer.abilities.flying = false
                        interactionPlayer.isNoGravity = false
                        interactionPlayer.noPhysics = false
                    }
                }
            }

            girl.setAnchored(false)
            girl.setInteractionPlayerUUID(null)
            girl.cameraOriginPos = null
            girl.isNoGravity = false
            girl.noPhysics = false
            val world = girl.level()
            var pos = girl.position()

            // 1.12 compared against Blocks.AIR; 1.21 also has cave/void air
            while (!world.getBlockState(BlockPos.containing(pos.x, pos.y, pos.z)).isAir) {
                pos = pos.add(0.0, 1.0, 0.0)
            }

            girl.teleportTo(pos.x, pos.y, pos.z)
        }

        @JvmStatic
        fun resetGirls(player: ServerPlayer?) {
            if (player != null) {
                val world = player.level()
                var pos = player.position()

                while (!world.getBlockState(BlockPos.containing(pos.x, pos.y, pos.z)).isAir) {
                    pos = pos.add(0.0, 1.0, 0.0)
                }

                player.teleportTo(pos.x, pos.y, pos.z)
                player.isInvisible = false
                player.noPhysics = false
                player.isNoGravity = false
                player.abilities.flying = false
                PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(true), player)
            }
        }
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ResetGirlPacket>(Main.rl("reset_girl"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ResetGirlPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> ResetGirlPacket(UUID.fromString(buf.readUtf()), buf.readBoolean()) })

        @JvmStatic
        fun handle(packet: ResetGirlPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.info("recieved an unvalid message @ResetGirl :(")
                return
            }
            val server = ServerLifecycleHooks.getCurrentServer() ?: return
            for (girl in BaseGirlEntity.girlList(packet.girlUUID)) {
                if (!girl.level().isClientSide) {
                    SceneDebug.log(SceneDebug.RESET, "ResetGirlPacket.onMessage girl=%s resetPose=%s action=%s anchored=%s interact=%s", girl.getDisplayNameText(), packet.resetPose, girl.getCurrentAction(), girl.isAnchored(), girl.getInteractionPlayerUUID())
                    val interactionUUID = girl.getInteractionPlayerUUID()
                    if (interactionUUID != null) {
                        Handler.resetGirls(server.playerList.getPlayer(interactionUUID))
                    }

                    // Jar-faithful (verified against Fapcraft.1.12.2.v1.1.jar
                    // bytecode): resetGirl() runs when resetPose == FALSE. The
                    // single-arg packet is the full scene-end reset (player
                    // physics via resetGirls + girl release via resetGirl); the
                    // two-arg TRUE packet is the player-only reset used by
                    // strip/doggy transitions where the girl keeps her pose.
                    if (!packet.resetPose) {
                        Handler.resetGirl(girl)
                    }
                }
            }
        }
    }
}
