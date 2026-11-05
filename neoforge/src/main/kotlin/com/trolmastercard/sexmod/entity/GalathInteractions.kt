package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.GalathFlightHud
import com.trolmastercard.sexmod.item.GalathCoinItem
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.RequestRidingPacket
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.ThreadNames
import java.util.UUID
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player

/**
 * <b>Role.</b> Galath's right-click entry points, split out of
 * [GalathEntity]: the master (tamed) action menu, the client action
 * dispatch that arms the scenes, and the corruption trigger on a knocked-out
 * wild girl.
 *
 * <b>Flow.</b> [processInteract] routes by master; [doAction] is called
 * back from the open `BeeScreen` menu and runs its 1200 ms arming thread.
 *
 * <b>Pitfalls.</b> The arming threads are 1200 ms delayed and read
 * the local player when they fire, not the click-time player.
 */
object GalathInteractions {
    @JvmStatic
    fun processInteract(galath: GalathEntity, player: Player, hand: InteractionHand): Boolean {
        return if (galath.hasMaster()) processMasterInteract(galath, player, hand) else processGirlInteract(galath, player, hand)
    }

    /**
     * SERVER: tamed interaction — only the master may interact; the action
     * menu depends on held items (no coin) and context: ride while airborne,
     * cowgirl/anal (+ threesome with a Manglelie) on the ground.
     */
    @JvmStatic
    fun processMasterInteract(galath: GalathEntity, player: Player, hand: InteractionHand): Boolean {
        if (player.uuid != galath.getMasterUUID()) {
            return false
        }

        if (Action.isAnyAction(galath, Action.HUG_MANG, Action.RUN, Action.GALATH_SUMMON, Action.GALATH_DE_SUMMON, Action.MASTERBATE)) {
            return false
        }

        if (GalathCoinItem.GALATH_COIN != player.getItemInHand(InteractionHand.OFF_HAND).item &&
            GalathCoinItem.GALATH_COIN != player.getItemInHand(InteractionHand.MAIN_HAND).item
        ) {
            galath.playRandomSound(SoundHandler.GIRLS_GALATH_HUH)
            val options: Array<String> = if (!player.onGround()) {
                arrayOf("ride")
            } else if (galath.getMangleliePartner(false) == null) {
                arrayOf("cowgirl", "anal", "ride")
            } else {
                arrayOf("cowgirl", "anal", "threesome", "ride")
            }

            if (galath.level().isClientSide) {
                BaseGirlEntity.openInventoryGui(player, galath.getSelf(), options, false)
            }

            return true
        } else {
            return false
        }
    }

    /**
     * CLIENT: master-action dispatch — `ride` shows the flight HUD and
     * requests riding; `anal`/`cowgirl`/`threesome` arm the
     * scene after a 1200 ms delay thread (anchor + start the action + bind
     * the player; the threesome also starts Manglelie on
     * [Action.THREESOME_SLOW] + [Action.PUSSY_LICKING]).
     */
    @JvmStatic
    fun doAction(galath: GalathEntity, action: String, uuid: UUID) {
        if ("ride" == action) {
            GalathFlightHud.showHud()
            PacketHandler.networkWrapper.sendToServer(RequestRidingPacket())
        } else if ("anal" == action) {
            BeeScreen.enableInteraction()
            HandlePlayerMovement.setMovementLock(false)
            ThreadNames.createDaemonThread(1200) {
                val player = ClientHooks.localPlayer()!! // java-npe
                galath.setTargetPosition(player.position())
                galath.setYawRotation(0.0f)
                galath.setInteractionPlayerUUID(player.uuid)
                galath.setAnchored(true)
                galath.setCurrentAction(Action.CORRUPT_SLOW)
            }
        } else if ("cowgirl" == action) {
            BeeScreen.enableInteraction()
            HandlePlayerMovement.setMovementLock(false)
            ThreadNames.createDaemonThread(1200) {
                val player = ClientHooks.localPlayer()!! // java-npe
                galath.setTargetPosition(player.position())
                galath.setYawRotation(player.yRot + 180.0f)
                galath.setCurrentAction(Action.RAPE_INTRO)
                galath.setInteractionPlayerUUID(player.uuid)
                galath.setAnchored(true)
            }
        } else {
            if ("threesome" == action) {
                val manglelie = galath.getMangleliePartner(false) ?: return

                BeeScreen.enableInteraction()
                HandlePlayerMovement.setMovementLock(false)
                ThreadNames.createDaemonThread(1200) {
                    val player = ClientHooks.localPlayer()!! // java-npe
                    ClientHooks.setThirdPersonView(1)
                    manglelie.setTargetPosition(player.position())
                    galath.setTargetPosition(player.position())
                    manglelie.setYawRotation(player.yRot + 180.0f)
                    galath.setYawRotation(player.yRot)
                    manglelie.setCurrentAction(Action.THREESOME_SLOW)
                    galath.setCurrentAction(Action.PUSSY_LICKING)
                    manglelie.setInteractionPlayerUUID(player.uuid)
                    galath.setInteractionPlayerUUID(player.uuid)
                    manglelie.setAnchored(true)
                    galath.setAnchored(true)
                }
            }
        }
    }

    /**
     * SERVER: the corruption trigger — a knocked-out (paralyzed) Galath being
     * right-clicked by a non-owner player starts [Action.CORRUPT_INTRO]
     * anchored and locks the player in.
     */
    @JvmStatic
    fun processGirlInteract(galath: GalathEntity, player: Player, hand: InteractionHand): Boolean {
        if (!galath.entityData.get(GalathEntity.PARALYZED)) {
            return galath.processInteractBase(player, hand)
        } else if (galath.getCurrentAction() != Action.KNOCK_OUT_GROUND) {
            return galath.processInteractBase(player, hand)
        } else if (galath.level().isClientSide) {
            player.setYRot(player.yRot - -128.0f)
            player.setXRot(19.0f)
            return true
        } else {
            galath.setCurrentAction(Action.CORRUPT_INTRO)
            galath.setInteractionPlayerUUID(player.uuid)
            galath.setAnchored(true)
            galath.setTargetPosition(galath.position())
            galath.setYawRotation(player.yRot)
            PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), player as ServerPlayer)
            player.teleportTo(galath.x, galath.y, galath.z)
            return true
        }
    }
}
