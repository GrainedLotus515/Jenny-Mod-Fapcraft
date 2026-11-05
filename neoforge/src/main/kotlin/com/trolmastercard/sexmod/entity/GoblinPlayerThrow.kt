package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import com.trolmastercard.sexmod.util.HandlePlayerMovement
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/**
 * <b>Role.</b> Carry and throw glue for [GoblinPlayerEntity]: the
 * sneak+click pickup trigger, the head glue while carried, the throw flight
 * countdown, the thrown/stand-up recovery and the client-side owner revival.
 * Every method here is the bookkeeping half of the overrides that stay on the
 * entity (`tick`, `updateAITasks`).
 *
 * <b>Flow.</b> [handlePlayerThrow] binds the carrier and starts
 * PICK_UP with the 45-tick hold countdown; [updatePlayerThrowProgress]
 * launches the carrying player at tick 15 and unbinds owner/interaction at
 * tick 39 (THROWN); [handlePlayerThrown] counts 30 grounded ticks into
 * STAND_UP; [handlePlayerStandUp] runs STAND_UP for 37 ticks back to
 * NULL. [handleThrowAction] is the glue that keeps the transformed
 * player 2 blocks above the carrier until the throw starts.
 */
object GoblinPlayerThrow {
    /**
     * SERVER: the pickup trigger (sneak+click by a non-transformed player on
     * the carried player's entity) — binds the carrier and starts
     * [Action.PICK_UP] with the 45-tick hold countdown, locking the
     * carrying player's physics.
     */
    @JvmStatic
    fun handlePlayerThrow(goblin: GoblinPlayerEntity, player: Player) {
        if (goblin.getCurrentAction() != Action.NULL || goblin.getOwnerUUID() != null) {
            return
        }

        if (GoblinEntity.hasGoblinWithUUID(player.uuid)) {
            player.displayClientMessage(Component.literal("you are already carrying a Goblin"), true)
            return
        }

        goblin.setOwnerUUID(player.uuid)
        goblin.setCurrentAction(Action.PICK_UP)
        goblin.setHeldPlayerDistance(45)
        val ownerPlayer = goblin.getOwnerPlayer() ?: return

        ownerPlayer.isNoGravity = true
        ownerPlayer.noPhysics = true
        if (!goblin.level().isClientSide) {
            PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), ownerPlayer as ServerPlayer)
        }
    }

    /**
     * SERVER (mirrored on CLIENT): while carried (owner set) and not yet
     * thrown, keeps the transformed player glued 2 blocks above the carrier.
     */
    @JvmStatic
    fun handleThrowAction(goblin: GoblinPlayerEntity) {
        val action = goblin.getCurrentAction()
        if (action == Action.THROWN) {
            return
        }

        if (action == Action.START_THROWING && goblin.getThrowProgress() > 15) {
            return
        }

        val uuid = goblin.getOwnerUUID() ?: return
        val owner = goblin.level().getPlayerByUUID(uuid) ?: return
        val player = goblin.getOwnerPlayer() ?: return

        player.noPhysics = true
        player.isNoGravity = true
        player.setPos(owner.x, owner.y + 2.0, owner.z)
    }

    /**
     * SERVER (mirrored on CLIENT): throw flight for the transformed player —
     * at tick 15 the carrying player is launched with the carrier's aim, at
     * tick 39 the throw ends (THROWN) and the owner/interaction bindings
     * clear.
     */
    @JvmStatic
    fun updatePlayerThrowProgress(goblin: GoblinPlayerEntity) {
        var progress = goblin.getThrowProgress()
        if (progress == -1) {
            return
        }

        progress++
        goblin.setThrowProgress(progress)
        val player = goblin.getOwnerPlayer() ?: return

        if (progress == 15) {
            GoblinEntity.getGoblinThrowPos(goblin)
            val pitch = GoblinEntity.getGoblinThrowHeight(goblin)
            val yaw = GoblinEntity.getGoblinThrowDistance(goblin)
            if (goblin.level().isClientSide && goblin.hasOwnerUUID()) {
                HandlePlayerMovement.setMovementLock(true)
            }

            val vec = GoblinEntity.rotateVectorPitchYaw(Vec3(0.0, 0.0, 1.5), pitch, yaw)
            player.setDeltaMovement(vec.x, vec.y, vec.z)
            if (!goblin.level().isClientSide) {
                goblin.setYawRotation(yaw)
            }
        }

        player.noPhysics = false
        player.isNoGravity = false
        if (progress == 39) {
            goblin.setThrowProgress(-1)
            goblin.setCurrentAction(Action.THROWN)
            goblin.setInteractionPlayerUUID(null)
            goblin.setOwnerUUID(null)
        }
    }

    @JvmStatic
    fun handlePlayerStandUp(goblin: GoblinPlayerEntity) {
        if (goblin.getCurrentAction() != Action.STAND_UP) {
            return
        }

        goblin.standUpTicks++
        if (goblin.standUpTicks >= 37) {
            goblin.standUpTicks = 0
            goblin.setCurrentAction(Action.NULL)
        }
    }

    @JvmStatic
    fun handlePlayerThrown(goblin: GoblinPlayerEntity) {
        if (goblin.getCurrentAction() != Action.THROWN) {
            return
        }

        val player = goblin.getOwnerPlayer()
        if (player == null || !player.onGround()) {
            return
        }

        val throwTick = goblin.getThrowTickCount() + 1
        goblin.setThrowTickCount(throwTick)
        if (throwTick >= 30) {
            goblin.setThrowTickCount(0)
            goblin.setCurrentAction(Action.STAND_UP)
        }
    }

    /**
     * CLIENT: revives the carrying player's entity while the throw is being
     * wound up, re-spawning it if the client already dropped it.
     */
    @JvmStatic
    fun handleOwnerThrow(goblin: GoblinPlayerEntity) {
        val player = goblin.getOwnerPlayer()
        if (player == null || goblin.getCurrentAction() != Action.START_THROWING) {
            return
        }

        Client.revive(player)
    }

    private object Client {
        fun revive(player: Player) {
            // PORT-GAP: 1.12 reset `isDead = false`; 1.21 `Entity.unsetRemoved` is protected, so a
            // removed player entity cannot be revived. The carry renderer no longer marks players
            // removed (see GoblinPlayerEntity.GoblinCarryEvents), so only the re-add remains.
            val level = Minecraft.getInstance().level ?: return
            if (!player.isRemoved && level.getEntity(player.id) == null) {
                level.addEntity(player)
            }
        }
    }
}
