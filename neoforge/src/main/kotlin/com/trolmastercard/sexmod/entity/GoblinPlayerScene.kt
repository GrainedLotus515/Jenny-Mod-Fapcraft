package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.ClientHooks

/**
 * Scene positioning for [GoblinPlayerEntity]: places the interaction player
 * for nelson / paizuri (server) and the client-side third-person force and
 * nelson variant reset.
 */
object GoblinPlayerScene {
    @JvmStatic
    fun handlePlayerLook(goblin: GoblinPlayerEntity) {
        val uuid = goblin.getInteractionPlayerUUID() ?: return
        val player = goblin.level().getPlayerByUUID(uuid) ?: return

        goblin.setYawRotation(player.yRot)
        goblin.noPhysics = true
        goblin.isNoGravity = true
        player.isNoGravity = true
        player.noPhysics = true
        player.teleportTo(player.x, player.y, player.z - 1.0)
    }

    @JvmStatic
    fun handlePlayerInteract(goblin: GoblinPlayerEntity) {
        val uuid = goblin.getInteractionPlayerUUID() ?: return
        val player = goblin.level().getPlayerByUUID(uuid) ?: return

        goblin.setYawRotation(player.yRot + 180.0f)
        goblin.noPhysics = true
        goblin.isNoGravity = true
        player.isNoGravity = true
        player.noPhysics = true
        player.teleportTo(player.x, player.y - 0.5, player.z - 0.6f)
        player.xRot = 70.0f
        player.xRotO = 70.0f
    }

    /**
     * CLIENT: forces third person for the nelson loop when the local player is
     * the interaction target and is still in first person.
     */
    @JvmStatic
    fun handleLocalAction(goblin: GoblinPlayerEntity, action: Action) {
        val interactionUuid = goblin.getInteractionPlayerUUID()
        if (ClientHooks.localPlayerUUID()!! != interactionUuid) { // java-npe
            return
        }

        if (ClientHooks.thirdPersonView() != 0) {
            return
        }

        when (action) {
            Action.NELSON_CUM, Action.NELSON_FAST, Action.NELSON_INTRO, Action.NELSON_SLOW -> ClientHooks.setThirdPersonView(2)
            else -> {}
        }
    }

    /** CLIENT. */
    @JvmStatic
    fun handleNelsonAction(goblin: GoblinPlayerEntity, action: Action) {
        if (action == Action.NELSON_FAST && goblin.getPreviousAction() != Action.NELSON_FAST) {
            goblin.nelsonFastVariantC = false
        }
    }
}
