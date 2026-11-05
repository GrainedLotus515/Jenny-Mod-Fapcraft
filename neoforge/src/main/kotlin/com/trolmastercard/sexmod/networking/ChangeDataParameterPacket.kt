package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.SlimeEntity
import com.trolmastercard.sexmod.util.SceneDebug
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.UUID

/**
 * CLIENT -> SERVER bridge that writes a single entity data-manager entry on
 * the server girl identified by [girlUUID].
 *
 * This is how every client-side scene mutation reaches the server. Supported
 * parameter names:
 * - `"currentAction"` — sets `BaseGirlEntity.CUR_ACTION`
 *   (guarded: ATTACK only allowed from NULL).
 * - `"animationFollowUp"` — sets `BaseGirlEntity.GIRL_HAND_STATES`,
 *   the scene-entry hand-state consumed by each girl's `startActionFromHandState()`.
 * - `"playerSheHasSexWith"` — sets/clears `BaseGirlEntity.INTERACTION_PARTNER_UUID`.
 * - `"targetPos"` — sets `BaseGirlEntity.TARGET_POS` (value is `xfyfz`-formatted).
 * - `"shouldbeattargetpos"` — sets `BaseGirlEntity.IS_ANCHORED`.
 * - `"currentModel"` — sets `BaseGirlEntity.OUTFIT_INDEX`.
 * - `"master"`, `"walk speed"`, `"pregnant"` — master UUID, walk state, slime
 *   horny level respectively. 1.21: `"pregnant"` only applies to [SlimeEntity]
 *   (the key is no longer shared across the hierarchy, §2.6).
 *
 * **Scene flow position:** the entry chain is
 * `doAction` (client) -> this packet (animationFollowUp) ->
 * [KoboldStatePacket] (dismount/position) -> `customServerAiStep`
 * lerp -> anchored -> `startActionFromHandState()` reads GIRL_HAND_STATES and starts the
 * scene action.
 */
class ChangeDataParameterPacket(
    @JvmField val girlUUID: UUID,
    @JvmField val parameterName: String,
    @JvmField val value: String?,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ChangeDataParameterPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        buf.writeUtf(girlUUID.toString())
        buf.writeUtf(parameterName)
        buf.writeUtf(value ?: "null")
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<ChangeDataParameterPacket>(Main.rl("change_data_parameter"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, ChangeDataParameterPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf -> ChangeDataParameterPacket(UUID.fromString(buf.readUtf()), buf.readUtf(), buf.readUtf()) })

        @JvmStatic
        fun handle(packet: ChangeDataParameterPacket, ctx: IPayloadContext) {
            val girl = BaseGirlEntity.getServerGirlEntity(packet.girlUUID) ?: return
            val value = packet.value ?: "null"
            SceneDebug.log(SceneDebug.PACKETS, "ChangeDataParameter: %s param=%s value=%s (remote=%s)", girl.getDisplayNameText(), packet.parameterName, value, girl.level().isClientSide)
            when (packet.parameterName) {
                "pregnant" -> if (girl is SlimeEntity) {
                    girl.getEntityData().set(SlimeEntity.HORNY_LEVEL, value.toInt())
                }
                "currentModel" -> girl.getEntityData().set(BaseGirlEntity.OUTFIT_INDEX, value.toInt())
                "currentAction" -> if (Action.valueOf(value) != Action.ATTACK || girl.getCurrentAction() == Action.NULL) {
                    girl.setCurrentAction(Action.valueOf(value))
                }
                "animationFollowUp" -> girl.getEntityData().set(BaseGirlEntity.GIRL_HAND_STATES, value)
                "playerSheHasSexWith" -> if (value == "null") {
                    girl.setInteractionPlayerUUID(null)
                } else {
                    girl.setInteractionPlayerUUID(UUID.fromString(value))
                }
                "targetPos" -> {
                    val parts = value.split("f")
                    val targetPos = Vec3(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble())
                    girl.setTargetPosition(targetPos)
                }
                "master" -> girl.getEntityData().set(BaseGirlEntity.MASTER, value)
                "walk speed" -> girl.getEntityData().set(BaseGirlEntity.WALK_SPEED, value)
                "shouldbeattargetpos" -> girl.getEntityData().set(BaseGirlEntity.IS_ANCHORED, value.toBoolean())
            }
        }
    }
}
