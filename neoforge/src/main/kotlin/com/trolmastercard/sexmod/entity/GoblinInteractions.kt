package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.client.gui.BeeScreen
import com.trolmastercard.sexmod.client.gui.GalathScreen
import com.trolmastercard.sexmod.entity.api.IGoblin
import com.trolmastercard.sexmod.networking.ResetGirlPacket
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import java.util.ConcurrentModificationException
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * Player interaction side of [GoblinEntity]: the right-click catch/pick-up
 * state machine (server), the GUI choices ("take ur stuff back" / "use her"),
 * the 15-tick paizuri/nelson start timers (client), the shoulder-goblin gesture
 * pad (G key) and the paizuri teardown.
 *
 * 1.21: [openGoblinPadForPlayer] no longer takes the `Minecraft` instance
 * (common class, Rule A); the client work runs in [Client].
 */
object GoblinInteractions {
    /**
     * CLIENT: action dispatch from the goblin's GUI — `take ur stuff
     * back` starts the throw, `use her` binds the acting player as the
     * throw/pickup target.
     */
    @JvmStatic
    fun doAction(goblin: GoblinEntity, action: String, uuid: UUID) {
        if ("take ur stuff back" == action) {
            goblin.setCurrentAction(Action.START_THROWING)
        }

        if ("use her" == action) {
            goblin.setThrowTarget(uuid)
        }
    }

    /**
     * CLIENT: arms the "use her" timer (`paizuriStartTicks` = 0) — after 15
     * ticks the paizuri scene starts (see [tickPaizuriStartTimer]).
     */
    @JvmStatic
    fun setThrowTarget(goblin: GoblinEntity, uuid: UUID) {
        goblin.paizuriStartTicks = 0
        BeeScreen.enableInteraction()
        HandlePlayerMovement.setMovementLock(false)
        goblin.setInteractionPlayerUUID(uuid)
    }

    /**
     * CLIENT: arms the nelson "pickup" timer (`nelsonStartTicks` = 0) — after
     * 15 ticks the nelson scene starts (see [tickNelsonStartTimer]).
     */
    @JvmStatic
    fun setPickupTarget(goblin: GoblinEntity, uuid: UUID) {
        goblin.nelsonStartTicks = 0
        BeeScreen.enableInteraction()
        HandlePlayerMovement.setMovementLock(false)
        goblin.setInteractionPlayerUUID(uuid)
    }

    /**
     * CLIENT: opens the goblin throw/pickup gesture pad (`GalathScreen`)
     * when the local player owns a goblin that is riding their shoulder
     * (SHOULDER_IDLE). Returns true if a pad was opened. Used by both the G
     * keybind and the RShift (Leave-sex-scene) dual keybind.
     */
    @JvmStatic
    fun openGoblinPadForPlayer(playerUuid: UUID?): Boolean {
        if (playerUuid == null) return false
        if (Client.isGalathScreenOpen()) return false
        var padGirl: BaseGirlEntity? = null
        try {
            for (girl in BaseGirlEntity.getGirlEntityList()) {
                if (girl.level().isClientSide && girl is IGoblin) {
                    if (playerUuid == (girl as IGoblin).getOwnerUUID()) {
                        padGirl = girl
                        break
                    }
                }
            }
        } catch (ex: ConcurrentModificationException) {
            return false
        }
        if (padGirl != null && padGirl.getCurrentAction() == Action.SHOULDER_IDLE) {
            Client.openPad(padGirl)
            return true
        }
        return false
    }

    @JvmStatic
    fun processInteract(goblin: GoblinEntity, player: Player, hand: InteractionHand): Boolean {
        if (goblin.level().isClientSide) {
            return true
        }

        if (goblin.isQueen) {
            return true
        }

        if (goblin.getCurrentAction() == Action.RUN) {
            if (goblin.distanceTo(player) > 3.5) {
                player.displayClientMessage(Component.literal("get a bit closer..."), true)
            } else {
                goblin.setTargetPosition(player.position())
                goblin.setYawRotation(player.yRot)
                goblin.setCurrentAction(Action.CATCH)
                goblin.getEntityData().set(BaseGirlEntity.GIRL_HAND_STATES, "bj")
                goblin.setOwnerUUID(player.uuid)
                goblin.setInteractionPlayerUUID(player.uuid)
                goblin.getNavigation().stop()
                goblin.setDeltaMovement(0.0, 0.0, 0.0)
            }

            return true
        } else {
            if (hasGoblinWithUUID(player.uuid)) {
                player.displayClientMessage(Component.literal("you are already carrying a Goblin"), true)
            } else {
                goblin.setOwnerUUID(player.uuid)
                goblin.setCurrentAction(Action.PICK_UP)
                goblin.heldPlayerDistance = 45
                goblin.setAnchored(false)
                goblin.getEntityData().set(GoblinEntity.TAMED, true)
                goblin.getNavigation().stop()
            }

            return true
        }
    }

    @JvmStatic
    fun hasGoblinWithUUID(uuid: UUID?): Boolean {
        if (uuid == null) {
            return false
        }

        try {
            for (girl in BaseGirlEntity.getGirlEntityList()) {
                if (girl is IGoblin && !girl.level().isClientSide && !girl.isRemoved) {
                    val candidateUuid = (girl as IGoblin).getOwnerUUID()
                    // WILD/unowned goblins have a null owner; the QUERY uuid is the
                    // receiver so a null candidate just fails the match (no NPE).
                    // (Original bytecode: var0.equals(var3) — query first. The rename
                    // swapped the operands, NPEing on every unowned goblin.)
                    if (uuid == candidateUuid) {
                        return true
                    }
                }
            }
        } catch (ex: ConcurrentModificationException) {
            // ignored: entity list mutated during iteration; retried on the next tick
        }

        return false
    }

    /**
     * SERVER: paizuri scene teardown — resets the bound player, unbinds,
     * un-anchors, restores physics, drops the stolen item and (untamed) sends
     * her home and despawns her.
     */
    @JvmStatic
    fun endPaizuriScene(goblin: GoblinEntity) {
        val uuid = goblin.getInteractionPlayerUUID()
        val player = if (uuid == null) null else goblin.level().getPlayerByUUID(uuid)
        if (player != null) {
            ResetGirlPacket.Handler.resetGirls(player as ServerPlayer)
        }

        goblin.setInteractionPlayerUUID(null)
        goblin.setAnchored(false)
        goblin.noPhysics = false
        goblin.isNoGravity = false
        goblin.getEntityData().set(GoblinEntity.STOLEN_ITEM, ItemStack.EMPTY)
        if (!goblin.getEntityData().get(GoblinEntity.TAMED)) {
            goblin.teleportTo(goblin.homePos.x, goblin.homePos.y, goblin.homePos.z)
            goblin.discard()
        }
    }

    @JvmStatic
    fun handlePlayerLook(goblin: GoblinEntity) {
        val uuid = goblin.getInteractionPlayerUUID()
        val player = if (uuid == null) null else goblin.level().getPlayerByUUID(uuid)
        if (player != null) {
            goblin.setOwnerUUID(null)
            goblin.setTargetPosition(player.position())
            goblin.setYawRotation(player.yRot)
            goblin.setAnchored(true)
            goblin.noPhysics = true
            goblin.isNoGravity = true
            player.isNoGravity = true
            player.noPhysics = true
            goblin.setInteractionPlayerUUID(player.uuid)
        }
    }

    @JvmStatic
    fun handlePlayerInteract(goblin: GoblinEntity) {
        val uuid = goblin.getInteractionPlayerUUID()
        val player = if (uuid == null) null else goblin.level().getPlayerByUUID(uuid)
        if (player != null) {
            goblin.setOwnerUUID(null)
            goblin.setTargetPosition(player.position())
            goblin.setYawRotation(player.yRot + 180.0f)
            goblin.setAnchored(true)
            goblin.noPhysics = true
            goblin.isNoGravity = true
            player.isNoGravity = true
            player.noPhysics = true
            goblin.setInteractionPlayerUUID(player.uuid)
            player.teleportTo(player.x, player.y - 0.5, player.z)
            player.xRot = 70.0f
            player.xRotO = 70.0f
        }
    }

    /**
     * SERVER: returns the stolen item stack to the interaction player's
     * inventory and clears the stolen-item key.
     */
    @JvmStatic
    fun returnStolenItem(goblin: GoblinEntity) {
        val heldItem = goblin.getEntityData().get(GoblinEntity.STOLEN_ITEM)
        if (!heldItem.isEmpty) {
            val uuid = goblin.getInteractionPlayerUUID()
            val player = if (uuid == null) null else goblin.level().getPlayerByUUID(uuid)
            if (player != null) {
                player.inventory.add(heldItem.copy())
                goblin.getEntityData().set(GoblinEntity.STOLEN_ITEM, ItemStack.EMPTY)
            }
        }
    }

    /** CLIENT. */
    @JvmStatic
    fun tickPaizuriStartTimer(goblin: GoblinEntity) {
        if (goblin.paizuriStartTicks != -1) {
            goblin.paizuriStartTicks++
            if (goblin.paizuriStartTicks == 15) {
                goblin.paizuriStartTicks = -1
                goblin.setCurrentAction(Action.PAIZURI_START)
                ClientHooks.closeScreen()
            }
        }
    }

    /** CLIENT. */
    @JvmStatic
    fun tickNelsonStartTimer(goblin: GoblinEntity) {
        if (goblin.nelsonStartTicks != -1) {
            goblin.nelsonStartTicks++
            if (goblin.nelsonStartTicks == 15) {
                goblin.nelsonStartTicks = -1
                goblin.setCurrentAction(Action.NELSON_INTRO)
                ClientHooks.closeScreen()
                ClientHooks.setThirdPersonView(2)
            }
        }
    }

    private object Client {
        fun isGalathScreenOpen(): Boolean = Minecraft.getInstance().screen is GalathScreen

        fun openPad(girl: BaseGirlEntity) {
            Minecraft.getInstance().setScreen(GalathScreen(girl))
        }
    }
}
