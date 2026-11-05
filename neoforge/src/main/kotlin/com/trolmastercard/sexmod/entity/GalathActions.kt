package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.item.GalathCoinItem
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.ResetGirlPacket
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.util.GirlSavedData
import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.VectorMath
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> Galath's action state machine and scene start/teardown hooks,
 * split out of [GalathEntity]: the `setCurrentAction` gate, the
 * coin grant, the rape/hug/morning-blowjob enders, the Manglelie partner spawn
 * and the repositioning that puts the bound player into a scene.
 *
 * <b>Flow.</b> Scenes enter through the packet and command entry points
 * ([handleRapeState], [startPussyLicking], [startMasterbateSitting]); every
 * transition runs the [setCurrentAction] gate, which then fires the
 * GIVE_COIN / HUG_MANG / MORNING_BLOWJOB_CUM enders.
 *
 * <b>Pitfalls.</b> The gate refuses to leave GALATH_DE_SUMMON and blocks the
 * cum loops from re-entering their own scene, so an action that fails to
 * advance is usually this gate, not the ticker. All teardown hooks are
 * SERVER-only; a client-side call would desync the player.
 */
object GalathActions {
    /**
     * SERVER: the state-machine gate — guards the cum loops and
     * GALATH_DE_SUMMON, persists the cum time via [GirlSavedData] for
     * the tamed cum scenes, and fires the GIVE_COIN
     * ([grantCoinAndBindMaster]), HUG_MANG ([endHugScene]) and
     * MORNING_BLOWJOB_CUM ([endMorningBlowjobScene]) teardown hooks.
     */
    @JvmStatic
    fun setCurrentAction(galath: GalathEntity, action: Action?) {
        val currentAction = galath.getCurrentAction()
        if (currentAction == Action.GALATH_DE_SUMMON) {
            return
        }

        if (currentAction == Action.CORRUPT_CUM && (action == Action.CORRUPT_FAST || action == Action.CORRUPT_SLOW)) {
            return
        }

        if (currentAction == Action.RAPE_CUM && action == Action.RAPE_ON_GOING) {
            return
        }

        if (currentAction == Action.MORNING_BLOWJOB_CUM && (action == Action.MORNING_BLOWJOB_SLOW || action == Action.MORNING_BLOWJOB_FAST)) {
            return
        }

        if (!galath.level().isClientSide && Action.isAny(currentAction, Action.CORRUPT_CUM, Action.RAPE_CUM, Action.MORNING_BLOWJOB_CUM)) {
            GirlSavedData.saveCumTime(galath.getInteractionPlayerUUID(), galath.level().gameTime)
        }

        if (action == Action.CORRUPT_SLOW) {
            galath.corruptHard = false
            if (currentAction == Action.CORRUPT_INTRO) {
                setFlying(galath, false)
            }

            if (galath.hasMaster() && currentAction == Action.NULL) {
                setFlying(galath, true)
            }
        }

        if (currentAction == Action.GIVE_COIN && action == Action.NULL && !galath.level().isClientSide) {
            grantCoinAndBindMaster(galath)
        }

        if (currentAction == Action.HUG_MANG && action == Action.NULL) {
            endHugScene(galath)
        }

        if (currentAction == Action.MORNING_BLOWJOB_CUM && action == Action.NULL) {
            endMorningBlowjobScene(galath)
        }

        galath.setCurrentActionBase(action)
    }

    /**
     * SERVER: ends the rape scene — once the RAPE_ON_GOING loop leaves for
     * RAPE_CUM she switches to flight repositioning (CHANGE_POSITION) and
     * releases the player.
     */
    @JvmStatic
    fun handleRapeState(galath: GalathEntity) {
        val action = galath.getCurrentAction()
        if (action == Action.RAPE_ON_GOING) {
            galath.activeFlight = GalathFlightData.CHANGE_POSITION
            GalathFlightData.CHANGE_POSITION.executeStart(galath)
            galath.setAnchored(false)
            galath.setCurrentAction(Action.FLY)
            val player = galath.getPlayerEntity()
            galath.setInteractionPlayerUUID(null)
            if (player != null) {
                PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(true), player as ServerPlayer)
            }

            BaseGirlEntity.girlPlaySound(galath, SoundHandler.GIRLS_GALATH_DIALOG[0])
        }
    }

    /**
     * SERVER: the morning-blowjob teardown — resets the bound player and the
     * girl after MORNING_BLOWJOB_CUM.
     */
    @JvmStatic
    fun endMorningBlowjobScene(galath: GalathEntity) {
        val player = galath.getPlayerEntity()
        if (player != null) {
            ResetGirlPacket.Handler.resetGirls(player as ServerPlayer)
        }

        ResetGirlPacket.Handler.resetGirl(galath)
    }

    @JvmStatic
    fun endHugScene(galath: GalathEntity) {
        galath.setAnchored(false)
        val manglelie = galath.getMangleliePartner(true)
        manglelie?.setCorrupting(true)
    }

    /**
     * SERVER: the coin grant — swaps the player's main hand for the
     * [GalathCoinItem], unbinds the scene, binds her to the player as
     * master and explains the coin mechanic in chat.
     */
    @JvmStatic
    fun grantCoinAndBindMaster(galath: GalathEntity) {
        val player = galath.getPlayerEntity()
        if (player != null) {
            val stack = player.mainHandItem
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(GalathCoinItem.GALATH_COIN))
            if (!stack.isEmpty) {
                player.inventory.add(stack)
            }

            PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(true), player as ServerPlayer)
            galath.setInteractionPlayerUUID(null)
            galath.setTargetEntity(null)
            player.sendSystemMessage(
                Component.literal(
                    ChatFormatting.GRAY.toString() +
                        "Defeating a succubus makes her accept the victor as her master, granting him a coin to which her soul is bound. Using the coin summons her, offering services on demand. If her master uses the coin on her or goes too far, she returns to the coin",
                ),
            )
            GirlSavedData.updateMangleliePartner(galath)
            player.teleportTo(player.x, Math.ceil(player.y) + 1.0, player.z)
        }
    }

    @JvmStatic
    fun setFlying(galath: GalathEntity, flying: Boolean) {
        val player = galath.getPlayerEntity()
        if (player != null) {
            val pos: Vec3 = if (flying) {
                Vec3(-0.5, (0.5f - player.eyeHeight).toDouble(), 0.4f.toDouble()).add(galath.getTargetPosition())
            } else {
                VectorMath.rotateByYaw(Vec3(0.5, (0.5f - player.eyeHeight).toDouble(), 0.4f.toDouble()), galath.getYawRotation())
                    .add(galath.getTargetPosition())
            }

            player.teleportTo(pos.x, pos.y, pos.z)
        }
    }

    @JvmStatic
    fun canStartPussyLicking(galath: GalathEntity): Boolean {
        if (galath.getMangleliePartner(true) != null) {
            return false
        }

        val manglelie = ManglelieEntity(galath.level())
        galath.setMangleliePartnerUUID(manglelie.getGirlId())
        manglelie.setGalathPartnerUUID(galath.getGirlId())
        manglelie.setCorrupting(true)
        manglelie.setCurrentAction(Action.RIDE_MOMMY_HEAD)
        manglelie.moveTo(galath.x, galath.y, galath.z)
        galath.level().addFreshEntity(manglelie)
        return true
    }

    @JvmStatic
    fun startMasterbateSitting(galath: GalathEntity) {
        if (galath.getCurrentAction() != Action.MASTERBATE_SITTING) {
            galath.masterbateSittingPending = true
            galath.setCurrentAction(Action.MASTERBATE_SITTING)
        }
    }

    @JvmStatic
    fun startPussyLicking(galath: GalathEntity) {
        galath.pussyLickingForward = true
        galath.setCurrentAction(Action.PUSSY_LICKING)
    }

    @JvmStatic
    fun resetInteractionState(galath: GalathEntity) {
        galath.setInteractionPlayerUUID(null)
        galath.setCurrentAction(null)
    }
}
