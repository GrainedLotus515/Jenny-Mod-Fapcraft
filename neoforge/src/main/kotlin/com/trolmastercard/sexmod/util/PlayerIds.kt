package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BiaPlayerEntity
import com.trolmastercard.sexmod.entity.ElliePlayerEntity
import com.trolmastercard.sexmod.entity.GalathEntity
import com.trolmastercard.sexmod.item.AlliesLampItem
import com.trolmastercard.sexmod.networking.InformOfOwnershipPacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.ResetGirlPacket
import com.trolmastercard.sexmod.networking.SendBlocksPacket
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.entity.EntityTypeTest
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import java.util.UUID

/**
 * **Role.** Login/logout handling for the horny-potion player-girl
 * transformation: on login the player is restored to vanilla state, his
 * player-girl (if any) is released from scenes, and the special fixed-UUID
 * players (Bia, Ellie) get their permanent player-girl re-registered; on logout
 * all girls he interacted with are reset so no scene is left half-open.
 *
 * **Login flow (SERVER-side events):** invisible/gravity/clip flags cleared,
 * flight disabled outside creative; movement lock + ownership status sent to the
 * client ([SetPlayerMovementPacket], [InformOfOwnershipPacket]);
 * Allies lamp NBT re-keyed; tribe markers resent ([SendBlocksPacket]);
 * player-girl table rebuilt, any existing girl un-anchored and fully reset via
 * [ResetGirlPacket.Handler.resetGirl]; fixed-UUID girls re-spawned
 * ([registerBia], [registerEllie]).
 *
 * **Logout flow:** every girl with the leaving player as interaction partner
 * is reset (single-arg full reset) and un-anchored; the girl's owner gets a
 * movement unlock. Concurrent-modification guards on the girl list must stay.
 */
class PlayerIds {
    @SubscribeEvent
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val server = event.entity.server ?: return
        val player = server.playerList.getPlayer(event.entity.uuid) ?: return
        player.isInvisible = false
        player.isNoGravity = false
        player.noPhysics = false
        if (!player.abilities.instabuild && player.abilities.flying) {
            player.abilities.flying = false
        }

        PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(true), player)
        PacketHandler.networkWrapper.sendTo(InformOfOwnershipPacket(GirlSavedData.hasOwner(player.uuid)), player)

        for (stack in player.inventory.items) {
            if (stack.item === AlliesLampItem.ALLIES_LAMP && ItemNbt.hasTag(stack)) {
                val tag = ItemNbt.getTag(stack)!! // java-npe: hasTag checked above
                tag.putUUID("user", UUID.randomUUID())
                ItemNbt.setTag(stack, tag)
            }
        }

        val tribeUuid = KoboldManager.getTribeUUID(player.uuid)
        if (tribeUuid != null) {
            val blocks = KoboldManager.getAllTribeBlocks(tribeUuid)
            PacketHandler.networkWrapper.sendTo(SendBlocksPacket(blocks, true), player)
        }

        AbstractPlayerGirlEntity.rebuildPlayerGirlTableFromWorld()
        val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(event.entity.uuid)
        val world: Level = server.overworld()
        this.registerJenny(world, player, playerGirl)
        if (playerGirl != null) {
            playerGirl.setAnchored(false)
            playerGirl.setCurrentAction(Action.NULL)
            ResetGirlPacket.Handler.resetGirl(playerGirl)
        }

        val playerUuid = event.entity.uuid
        if (playerUuid == playerId) {
            this.registerBia(world, player, playerUuid)
        }

        if (playerUuid == girlId) {
            this.registerEllie(world, player, playerUuid)
        }

        GalathEntity.handlePlayerJoin(player)
    }

    fun registerBia(world: Level, player: Player, uuid: UUID) {
        val bia = BiaPlayerEntity(world, uuid)
        bia.isNoGravity = true
        bia.noPhysics = true
        bia.setDeltaMovement(0.0, 0.0, 0.0)
        bia.setPos(player.x, player.y + 69.0, player.z)
        world.addFreshEntity(bia)
        bia.onTransformationSpawned()
    }

    fun registerEllie(world: Level, player: Player, uuid: UUID) {
        val ellie = ElliePlayerEntity(world, uuid)
        ellie.isNoGravity = true
        ellie.noPhysics = true
        ellie.setDeltaMovement(0.0, 0.0, 0.0)
        ellie.setPos(player.x, player.y + 69.0, player.z)
        world.addFreshEntity(ellie)
        ellie.onTransformationSpawned()
    }

    /** Removes stale duplicates of the player's player-girl (all loaded ones except `existingGirl`). */
    fun registerJenny(world: Level, player: Player, existingGirl: AbstractPlayerGirlEntity?) {
        val serverLevel = world as? ServerLevel ?: return
        for (playerGirl in serverLevel.getEntities(EntityTypeTest.forClass(AbstractPlayerGirlEntity::class.java)) { true }) {
            if (playerGirl.getOwnerUserUUID() == player.uuid && (existingGirl == null || playerGirl.id != existingGirl.id)) {
                playerGirl.discard()
            }
        }
    }

    @SubscribeEvent
    fun onPlayerLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        val player = event.entity

        try {
            for (girl in BaseGirlEntity.getGirlEntityList()) {
                if (girl is AbstractPlayerGirlEntity) {
                    girl.onOwnerInteract(player)
                }

                if (girl.getInteractionPlayerUUID() != null) {
                    if (girl.getInteractionPlayerUUID() == player.uuid) {
                        ResetGirlPacket.Handler.resetGirl(girl)
                        girl.setAnchored(false)
                        girl.setCurrentAction(Action.NULL)
                    }

                    val interactionUUID = girl.getInteractionPlayerUUID()
                    if (girl is AbstractPlayerGirlEntity
                        && girl.getOwnerUserUUID() == player.uuid
                        && interactionUUID != null
                    ) {
                        val ridingPlayer = player.level().getPlayerByUUID(interactionUUID) as ServerPlayer?
                        if (ridingPlayer != null) {
                            PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(true), ridingPlayer)
                        }
                        ResetGirlPacket.Handler.resetGirls(ridingPlayer)
                        player.isInvisible = false
                        girl.setInteractionPlayerUUID(null)
                    }
                }
            }
        } catch (exception: ConcurrentModificationException) {
            // ignored: entity list mutated during iteration; retried on the next tick
        }
    }

    companion object {
        @JvmField val playerId: UUID = UUID.fromString("b91e6484-8911-4def-ab04-9fa3452fca5f")
        @JvmField val girlId: UUID = UUID.fromString("adf20149-2adc-4a9d-9af5-8e9aeda019d6")
    }
}
