package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SetPlayerMovementPacket
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The goblin queen's AI for [GoblinEntity]: pregnancy timeout, guard
 * release after breeding, the breeding scan in front of the throne, the
 * jump-throw onto the player, guard (re)spawning, the gold-steal cycle and
 * the thief's flee pathing.
 *
 * 1.21: `getTotalWorldTime()` is `level.gameTime`; `isAirBorne` is
 * `hasImpulse`; `inventory.mainInventory` is `inventory.items`.
 */
object GoblinQueenAi {
    @JvmStatic
    fun handleHeldThrow(queen: GoblinEntity) {
        if (queen.isQueen) {
            if (queen.getEntityData().get(GoblinEntity.PREGNANT)) {
                if (queen.impregnationTick + 8400L < queen.level().gameTime) {
                    queen.getEntityData().set(GoblinEntity.PREGNANT, false)
                }
            }
        }
    }

    @JvmStatic
    fun handleHeldParticles(queen: GoblinEntity) {
        if (queen.isQueen) {
            if (queen.guards.isNotEmpty()) {
                var particlesSpawned = false

                for (goblin in queen.guards) {
                    if (goblin.getEntityData().get(GoblinEntity.TAMED)) {
                        particlesSpawned = true
                    }
                }

                if (particlesSpawned) {
                    queen.sendGirlChatMessage("Farewell my knight. You are welcome once I am breedable again.")

                    for (goblin in queen.guards) {
                        if (!goblin.getEntityData().get(GoblinEntity.TAMED)) {
                            goblin.setCurrentAction(Action.VANISH)
                        }
                    }

                    queen.guards.clear()
                    queen.setInteractionPlayerUUID(null)
                }
            }
        }
    }

    /**
     * SERVER: ends the breeding — after 100 ticks the queen unbinds the
     * player, releases both guards anchored as AWAIT_PICK_UP (rewards) and
     * restores the player's physics.
     */
    @JvmStatic
    fun handleThrowCooldown(queen: GoblinEntity) {
        if (queen.isQueen) {
            if (queen.throwCooldown != -1) {
                queen.throwCooldown++
                if (queen.throwCooldown >= 100) {
                    queen.throwCooldown = -1
                    val uuid = queen.getInteractionPlayerUUID()
                    if (uuid == null) {
                        queen.resetCameraAndPhysics()
                    } else {
                        val player = queen.level().getPlayerByUUID(uuid)
                        if (player == null) {
                            queen.resetCameraAndPhysics()
                        } else {
                            queen.setInteractionPlayerUUID(null)

                            for (goblin in queen.guards) {
                                goblin.setInteractionPlayerUUID(null)
                            }

                            val positions = ensureGuardsSpawned(queen)
                            val yaw = queen.throneYaw + 180.0f
                            val leftPos = queen.thronePos.add(GoblinThrow.rotateVectorYaw(GoblinEntity.GUARD_LEFT_OFFSET, yaw))
                            val rightPos = queen.thronePos.add(GoblinThrow.rotateVectorYaw(GoblinEntity.GUARD_RIGHT_OFFSET, yaw))
                            val playerPos = queen.thronePos.add(GoblinThrow.rotateVectorYaw(GoblinEntity.BREEDING_PLAYER_OFFSET, yaw))
                            val leftGoblin = positions[0]
                            val rightGoblin = positions[1]
                            leftGoblin.setTargetPosition(leftPos)
                            rightGoblin.setTargetPosition(rightPos)
                            leftGoblin.setYawRotation(0.0f)
                            rightGoblin.setYawRotation(0.0f)
                            leftGoblin.setAnchored(true)
                            rightGoblin.setAnchored(true)
                            leftGoblin.setCurrentAction(Action.AWAIT_PICK_UP)
                            rightGoblin.setCurrentAction(Action.AWAIT_PICK_UP)
                            leftGoblin.isNoGravity = false
                            rightGoblin.isNoGravity = false
                            player.isNoGravity = false
                            leftGoblin.noPhysics = false
                            rightGoblin.noPhysics = false
                            player.noPhysics = false
                            player.yRot = yaw
                            player.xRot = 30.0f
                            player.teleportTo(playerPos.x, playerPos.y, playerPos.z)
                            PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(true), player as ServerPlayer)
                            queen.sendGirlChatMessage(
                                "Thanks to you, my clan is soon going to get a few new members! In return I will bear of one of my guards to serve as your personal Onahole. Choose wisely~",
                            )
                        }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun handleHoldCooldown(queen: GoblinEntity) {
        if (queen.isQueen) {
            if (queen.holdCooldown != -1) {
                queen.holdCooldown++
                if (queen.holdCooldown >= 205) {
                    queen.holdCooldown = -1
                    val uuid = queen.getInteractionPlayerUUID()
                    if (uuid != null) {
                        val player = queen.level().getPlayerByUUID(uuid)
                        if (player != null) {
                            var pos = GoblinThrow.rotateVectorYaw(Vec3(0.0, 0.15625 - player.eyeHeight, -0.8859375), queen.throneYaw - 180.0f)
                            pos = pos.add(queen.getTargetPosition())
                            player.teleportTo(pos.x, pos.y, pos.z)
                        }
                    }
                }
            }
        }
    }

    /**
     * SERVER: throws the guards onto the breeding player — after 26 ticks in
     * [Action.JUMP_0] the queen repositions (per throne rotation) and
     * starts the breeding intro for herself and both guards; the 205-tick hold
     * cooldown (`holdCooldown`) then positions the player.
     */
    @JvmStatic
    fun handleJumpThrow(queen: GoblinEntity) {
        if (queen.isQueen) {
            if (queen.getCurrentAction() == Action.JUMP_0) {
                queen.jumpThrowTicks++
                if (queen.jumpThrowTicks >= 26) {
                    queen.jumpThrowTicks = 0
                    val throwPos = when (queen.throneYaw.toInt()) {
                        -90 -> queen.thronePos.add(GoblinEntity.THROW_OFFSET_YAW_NEG_90)
                        90 -> queen.thronePos.add(GoblinEntity.THROW_OFFSET_YAW_90)
                        180 -> queen.thronePos.add(GoblinEntity.THROW_OFFSET_YAW_180)
                        else -> queen.thronePos.add(GoblinEntity.THROW_OFFSET_YAW_0)
                    }

                    val uuid = queen.getInteractionPlayerUUID()
                    if (uuid != null) {
                        val player = queen.level().getPlayerByUUID(uuid)
                        if (player != null) {
                            queen.setTargetPosition(throwPos)
                            queen.setYawRotation(queen.throneYaw)
                            queen.setCurrentAction(Action.BREEDING_INTRO_0)
                            queen.noPhysics = true
                            queen.isNoGravity = true
                            val pos = GoblinThrow.rotateVectorYaw(Vec3(0.0, 0.44375 - player.eyeHeight, -0.7875), queen.throneYaw - 180.0f)
                            player.noPhysics = true
                            player.isNoGravity = true
                            player.teleportTo(pos.x + throwPos.x, pos.y + throwPos.y, pos.z + throwPos.z)
                            val goblins = ensureGuardsSpawned(queen)
                            if (goblins.size >= 1) {
                                val goblin = goblins[0]
                                goblin.setTargetPosition(throwPos)
                                goblin.setYawRotation(queen.throneYaw)
                                goblin.setCurrentAction(Action.BREEDING_INTRO_1)
                                goblin.noPhysics = true
                                goblin.isNoGravity = true
                            }

                            if (goblins.size >= 2) {
                                val goblin2 = goblins[1]
                                goblin2.setTargetPosition(throwPos)
                                goblin2.setYawRotation(queen.throneYaw)
                                goblin2.setCurrentAction(Action.BREEDING_INTRO_2)
                                goblin2.noPhysics = true
                                goblin2.isNoGravity = true
                            }

                            queen.holdCooldown = 0
                        }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun createThrowHitbox(min: Vec3, max: Vec3): AABB = AABB(min.x, min.y, min.z, max.x, max.y, max.z)

    /**
     * SERVER: queen breeding — scans the throne zone in front of the queen
     * (per throne rotation) for a grounded player; when found (and not
     * pregnant) locks them in and starts the breeding intro
     * ([Action.JUMP_0], guards get JUMP_1/JUMP_2).
     */
    @JvmStatic
    fun tryStartBreeding(queen: GoblinEntity) {
        if (queen.isQueen) {
            if (queen.getInteractionPlayerUUID() == null) {
                val basePos: Vec3? = when (queen.throneYaw.toInt()) {
                    -90 -> GoblinEntity.THRONE_OFFSET_YAW_NEG_90
                    0 -> GoblinEntity.THRONE_OFFSET_YAW_0
                    90 -> GoblinEntity.THRONE_OFFSET_YAW_90
                    180 -> GoblinEntity.THRONE_OFFSET_YAW_180
                    else -> null
                }

                if (basePos != null) {
                    val offset = queen.thronePos.subtract(0.5, 0.0, 0.5).subtract(basePos)
                    val aabb = createThrowHitbox(
                        offset,
                        offset.add(GoblinEntity.THRONE_SIZE.x.toDouble(), GoblinEntity.THRONE_SIZE.y.toDouble(), GoblinEntity.THRONE_SIZE.z.toDouble()),
                    )
                    val players = queen.level().getEntitiesOfClass(Player::class.java, aabb)
                    if (players.isNotEmpty()) {
                        val player = players[0]
                        if (player.onGround()) {
                            if (queen.getEntityData().get(GoblinEntity.PREGNANT)) {
                                if (queen.pregnantNoticeTick + 1200L < queen.level().gameTime) {
                                    player.displayClientMessage(Component.literal("The Queen is still pregnant - so no breeding for you uwu"), true)
                                    queen.pregnantNoticeTick = queen.level().gameTime
                                }
                            } else {
                                val uuid = player.uuid
                                val pos = player.position()
                                val yaw = player.yRot + 180.0f
                                PacketHandler.networkWrapper.sendTo(SetPlayerMovementPacket(false), player as ServerPlayer)
                                queen.setInteractionPlayerUUID(uuid)
                                queen.setCurrentAction(Action.JUMP_0)
                                queen.setTargetPosition(pos)
                                queen.setYawRotation(yaw)
                                queen.setAnchored(true)
                                val goblins = ensureGuardsSpawned(queen)
                                if (goblins.size > 0) {
                                    val goblin = goblins[0]
                                    goblin.setInteractionPlayerUUID(uuid)
                                    goblin.setCurrentAction(Action.JUMP_1)
                                    goblin.setTargetPosition(pos)
                                    goblin.setYawRotation(yaw)
                                    goblin.setAnchored(true)
                                    if (goblins.size > 1) {
                                        val goblin2 = goblins[1]
                                        goblin2.setInteractionPlayerUUID(uuid)
                                        goblin2.setCurrentAction(Action.JUMP_2)
                                        goblin2.setTargetPosition(pos)
                                        goblin2.setYawRotation(yaw)
                                        goblin2.setAnchored(true)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * SERVER: returns the queen's two guard goblins, respawning them (same
     * model part, her girl id as queen key) when fewer than two exist.
     */
    @JvmStatic
    fun ensureGuardsSpawned(queen: GoblinEntity): MutableList<GoblinEntity> {
        if (queen.guards.size > 1) {
            return queen.guards
        }

        for (goblin in queen.guards) {
            goblin.discard()
        }

        queen.guards.clear()
        val goblin = GoblinEntity(queen.level(), queen.getGirlId().toString(), queen.getModelPartIndex())
        goblin.setPos(queen.x, queen.y, queen.z)
        queen.level().addFreshEntity(goblin)
        queen.guards.add(goblin)
        val goblin2 = GoblinEntity(queen.level(), queen.getGirlId().toString(), queen.getModelPartIndex())
        goblin2.setPos(queen.x, queen.y, queen.z)
        queen.level().addFreshEntity(goblin2)
        queen.guards.add(goblin2)
        return queen.guards
    }

    @JvmStatic
    fun fleeFromNearestPlayer(queen: GoblinEntity) {
        if (queen.onGround()) {
            if (queen.getCurrentAction() == Action.RUN) {
                val player = queen.level().getNearestPlayer(queen, 100.0)
                if (player != null) {
                    var distance = 20.0

                    while (!(distance <= 0.0)) {
                        val delta = queen.position().subtract(player.position())
                        val absDelta = Vec3(Math.abs(delta.x), Math.abs(delta.y), Math.abs(delta.z))
                        val xWeight = absDelta.x / (absDelta.x + absDelta.z)
                        val zWeight = absDelta.z / (absDelta.x + absDelta.z)
                        val targetPos = queen.position().add(
                            Vec3((if (delta.x > 0.0) 1 else -1) * xWeight * distance, 0.0, (if (delta.z > 0.0) 1 else -1) * zWeight * distance),
                        )
                        val navigator = queen.getNavigation()
                        navigator.stop()
                        val moved = navigator.moveTo(targetPos.x, targetPos.y, targetPos.z, 0.825f.toDouble())
                        distance--
                        if (moved) {
                            return
                        }
                    }
                }
            }
        }
    }

    /**
     * SERVER: the queen's gold-steal cycle — every 32000 ticks while sitting
     * and unowned she spawns a RUN goblin that grabs a random gold item from
     * the nearest grounded player's inventory and brings it home.
     */
    @JvmStatic
    fun tickGoldSteal(queen: GoblinEntity) {
        if (queen.isQueen) {
            if (!queen.getEntityData().get(GoblinEntity.TAMED)) {
                if (!queen.getEntityData().get(GoblinEntity.PREGNANT)) {
                    if (queen.getCurrentAction() == Action.SIT) {
                        queen.robTicks++
                        if (queen.robTicks >= 32000) {
                            val player = queen.level().getNearestPlayer(queen, 3000.0)
                            if (player != null) {
                                if (player.onGround()) {
                                    if (!player.hasImpulse) {
                                        val slotIndex = findThrowTarget(queen, player)
                                        if (slotIndex != null) {
                                            val playerPos = player.position()
                                            val selfPos = queen.position()
                                            val delta = playerPos.subtract(selfPos)
                                            val dist = Math.sqrt(delta.x * delta.x + delta.z * delta.z)
                                            if (!(dist > 100.0)) {
                                                val stolenStack = player.inventory.getItem(slotIndex).copy()
                                                val goblin = GoblinEntity(queen.level(), queen.getGirlId().toString(), queen.getModelPartIndex())
                                                val offset = GoblinThrow.rotateVectorYaw(Vec3(0.0, 0.0, (-0.2f).toDouble()), player.yHeadRot)
                                                goblin.setPos(player.x + offset.x, player.y, player.z + offset.z)
                                                goblin.setCurrentAction(Action.RUN)
                                                queen.level().addFreshEntity(goblin)
                                                goblin.getEntityData().set(GoblinEntity.STOLEN_ITEM, stolenStack)
                                                player.sendSystemMessage(
                                                    Component.literal(String.format("<%s> I got your %s hehe~", goblin.getDisplayNameText(), stolenStack.hoverName.string)),
                                                )
                                                player.inventory.removeItemNoUpdate(slotIndex)
                                                queen.robTicks = 0
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun findThrowTarget(queen: GoblinEntity, player: Player): Int? {
        val inventory = player.inventory.items
        val validSlots = ArrayList<Int>()

        for (i in inventory.indices) {
            val stack = inventory[i]
            if (!stack.isEmpty && GoblinEntity.GOLD_ITEMS.contains(stack.item)) {
                validSlots.add(i)
            }
        }

        return if (validSlots.isEmpty()) null else validSlots[queen.random.nextInt(validSlots.size)]
    }

    @JvmStatic
    fun handleThrowState(queen: GoblinEntity) {
        if (queen.isQueen) {
            if (queen.getInteractionPlayerUUID() == null) {
                queen.setTargetPosition(queen.thronePos)
                queen.setYawRotation(queen.throneYaw)
                queen.setAnchored(true)
                queen.isNoGravity = true
                queen.setCurrentAction(Action.SIT)
            }
        }
    }
}
