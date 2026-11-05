package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.EllieEntity
import com.trolmastercard.sexmod.entity.JennyEntity
import com.trolmastercard.sexmod.entity.LunaEntity
import com.trolmastercard.sexmod.entity.api.IEllie
import com.trolmastercard.sexmod.util.SceneDebug
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * Scene-entry trigger packet, CLIENT -> SERVER. Despite the name it is the
 * shared "start a scene with a girl" packet used by every NPC girl.
 *
 * Constructor arg order is confusing: [tribeId] actually carries the
 * **girl's** UUID, [girlId] carries the **player's** UUID.
 *
 * SERVER side, [Handler.sendState] does:
 * 1. finds the server girl by her UUID (`BaseGirlEntity.girlList`);
 * 2. for Jenny/Ellie/Luna removes the wander + watch goals;
 * 3. clears her path/motion, binds the interaction player if unset, sets
 *    `TARGET_POS` to the front of the player, snaps the player next
 *    to her (`snapPlayerToPosition`);
 * 4. if [isSneaking] is true (always true from scene entry) and the
 *    girl implements [IEllie], calls `setDismounted()` — which
 *    starts the girl's 40-tick lerp to her target position in
 *    `customServerAiStep`, then `startActionFromHandState()` starts the scene.
 *
 * **Pitfall:** [followMode]/[isSneaking] are effectively always
 * `true` from scene entry (`triggerActionSync(true, true, uuid)`).
 */
class KoboldStatePacket(
    @JvmField val tribeId: UUID,
    @JvmField val girlId: UUID?,
    @JvmField val isSneaking: Boolean,
    @JvmField val followMode: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<KoboldStatePacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(tribeId.toString())
        buf.writeBoolean(isSneaking)
        buf.writeBoolean(followMode)
        buf.writeUtf(girlId?.toString() ?: "null")
    }

    object Handler {
        @JvmStatic
        fun sendState(tribeId: UUID, girlId: UUID?, isSneaking: Boolean, followMode: Boolean) {
            SceneDebug.log(SceneDebug.PACKETS, "KoboldStatePacket.sendState girl=%s player=%s isSneaking=%s followMode=%s", tribeId, girlId, isSneaking, followMode)
            try {
                for (girl in BaseGirlEntity.girlList(tribeId)) {
                    if (!girl.level().isClientSide) {
                        SceneDebug.log(SceneDebug.PACKETS, "KoboldStatePacket: girl %s found (%s), wander=%s watch=%s", girl.getDisplayNameText(), girl.javaClass.simpleName, girl.wanderGoal != null, girl.watchClosestGirlGoal != null)
                        if (girl is JennyEntity || girl is EllieEntity || girl is LunaEntity) {
                            girl.watchClosestGirlGoal?.let { girl.goalSelector.removeGoal(it) }
                            girl.wanderGoal?.let { girl.goalSelector.removeGoal(it) }
                        }

                        girl.navigation.stop()
                        girl.setDeltaMovement(0.0, 0.0, 0.0)
                        if (girl.getInteractionPlayerUUID() == null) {
                            girl.setInteractionPlayerUUID(girlId)
                        }

                        if (followMode) {
                            girl.setTargetPosition(girl.getFrontOffsetVector())
                            SceneDebug.log(SceneDebug.PACKETS, "KoboldStatePacket: targetPos=%s", girl.getTargetPosition())
                        }

                        girl.snapPlayerToPosition(girl.getInteractionPlayerUUID()!!) // java-npe: 1.12 NPE'd on a null partner as well
                        SceneDebug.log(SceneDebug.PACKETS, "KoboldStatePacket: after snap, girl pos=%s", girl.position())
                        if (!isSneaking) {
                            return
                        }

                        if (girl !is IEllie) {
                            SceneDebug.log(SceneDebug.PACKETS, "KoboldStatePacket: %s is not IEllie, no setDismounted", girl.javaClass.simpleName)
                            return
                        }

                        val ellie = girl as IEllie
                        ellie.setDismounted()
                        SceneDebug.log(SceneDebug.PACKETS, "KoboldStatePacket: setDismounted() called on %s", girl.getDisplayNameText())
                    }
                }
            } catch (exception: ConcurrentModificationException) {
                // ignored: entity list mutated during iteration; retried on the next tick
            }
        }
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<KoboldStatePacket>(Main.rl("kobold_state"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, KoboldStatePacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf ->
                val tribeId = UUID.fromString(buf.readUtf())
                val isSneaking = buf.readBoolean()
                val followMode = buf.readBoolean()
                val girlIdString = buf.readUtf()
                KoboldStatePacket(tribeId, if (girlIdString == "null") null else UUID.fromString(girlIdString), isSneaking, followMode)
            })

        @JvmStatic
        fun handle(packet: KoboldStatePacket, ctx: IPayloadContext) {
            if (ctx.flow().isServerbound) {
                Handler.sendState(packet.tribeId, packet.girlId, packet.isSneaking, packet.followMode)
            }
        }
    }
}
