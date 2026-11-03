package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.NpcType
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.world.level.Level
import net.neoforged.neoforge.network.handling.IPayloadContext
import java.util.Optional
import java.util.UUID

/**
 * **Role.** CLIENT->SERVER horny-potion transformation — toggles the sending
 * player between his vanilla self and a player-girl entity of the given [NpcType].
 *
 * **Handler.** SERVER-side.
 * - `npcType == null` ("player"): revert — remove every entity matching
 *   the girl's id, clear the girl registry entry
 *   (`AbstractPlayerGirlEntity.PLAYER_GIRL_BY_OWNER`) and un-set the owner id.
 * - `npcType != null`: spawn a fresh player-girl of that type 69 blocks
 *   above the player (reflection constructor `(Level, UUID)`), gravity
 *   off, no-clip, and call `onTransformationSpawned()` to finalize her state.
 *
 * **Pitfall.** The revert branch removes entities — the concurrent-modification
 * guard around the girl-list iteration must stay. The spawn height is
 * intentionally far above the player; her AI drops her in place.
 */
class UpdatePlayerModelPacket(@JvmField val npcType: NpcType?) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<UpdatePlayerModelPacket> = TYPE

    fun write(buf: FriendlyByteBuf) {
        if (npcType == null) {
            buf.writeUtf("player")
        } else {
            buf.writeUtf(npcType.toString())
        }
    }

    companion object {
        @JvmField
        val TYPE = CustomPacketPayload.Type<UpdatePlayerModelPacket>(Main.rl("update_player_model"))

        @JvmField
        val STREAM_CODEC: StreamCodec<FriendlyByteBuf, UpdatePlayerModelPacket> = StreamCodec.of(
            { buf, p -> p.write(buf) },
            { buf ->
                val typeName = buf.readUtf()
                UpdatePlayerModelPacket(if ("player" == typeName) null else NpcType.valueOf(typeName))
            })

        @JvmStatic
        fun handle(packet: UpdatePlayerModelPacket, ctx: IPayloadContext) {
            if (!ctx.flow().isServerbound) {
                Main.LOGGER.warn("received an invalid message @UpdatePlayerModel :(")
                return
            }
            val player = ctx.player()
            val world = player.level()
            val playerUuid = player.uuid
            val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(playerUuid)
            if (playerGirl != null) {
                try {
                    for (girl in BaseGirlEntity.getGirlEntityList()) {
                        if (!girl.level().isClientSide && girl.getGirlId() == playerGirl.getGirlId()) {
                            girl.discard()
                        }
                    }
                } catch (exception: ConcurrentModificationException) {
                    // ignored: girl list mutated while the stale entity was removed; the sync is repeated
                }

                playerGirl.onTickClient()
                AbstractPlayerGirlEntity.PLAYER_GIRL_BY_OWNER.remove(playerUuid)
                // 1.12 also called getGirlEntityList().remove(playerGirl): a no-op on the fresh copy the
                // list getter returns, omitted.
                playerGirl.setOwnerId(Optional.empty())
            }

            val npcType = packet.npcType
            if (npcType != null) {
                val newPlayerGirl: AbstractPlayerGirlEntity
                try {
                    val playerClass = npcType.playerClass ?: throw NullPointerException("$npcType has no player class") // java-npe: caught below
                    val constructor = playerClass.getConstructor(Level::class.java, UUID::class.java)
                    newPlayerGirl = constructor.newInstance(world, player.uuid) as AbstractPlayerGirlEntity
                } catch (exception: Exception) {
                    Main.LOGGER.error("could not instantiate the replacement player girl", exception)
                    return
                }

                newPlayerGirl.isNoGravity = true
                newPlayerGirl.noPhysics = true
                newPlayerGirl.setDeltaMovement(0.0, 0.0, 0.0)
                newPlayerGirl.setPos(player.x, player.y + 69.0, player.z)
                world.addFreshEntity(newPlayerGirl)
                newPlayerGirl.onTransformationSpawned()
            }
        }
    }
}
