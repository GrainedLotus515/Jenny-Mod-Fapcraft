package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.ClientHooks
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.AlliePlayerEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BeePlayerEntity
import com.trolmastercard.sexmod.entity.SlimePlayerEntity
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ScreenEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent

/**
 * <b>Role.</b> Event handlers around the player-girl transformation
 * ([AbstractPlayerGirlEntity]) and the player's interactions with other
 * player-girls:
 * - bed placement / sleep restrictions while transformed (girl must be
 *   placed into her bed, not the player's)
 * - bed-side placement of the player next to the girl's bed (`openBedsideInteractionMenu`
 *   client-side, anchored target server-side)
 * - respawn sync of the player-girl to the player's new position
 * - interaction menu between player-girls (lesbo prompt), slime doggy start,
 *   fall-damage immunity (Allie/Bee/Slime forms)
 * - dressup/strip button injected into the vanilla inventory GUI (1.12 id
 *   `284453`)
 *
 * <b>Pitfalls.</b> `HandlePlayerMovement.setMovementLock(false)` is
 * released on both sides when the girl is bed-placed or strips — forgetting the
 * server-side unlock leaves the player unable to move. The GUI button id and
 * [eventCooldown] are the same constant; keep them equal.
 *
 * Port notes (PORTING.md §2.3 rule E / §2.4): the 1.12 `@SideOnly(CLIENT)`
 * handlers (entity-interact menus, inventory button) live in [ClientEvents],
 * registered from the client path. `GetCollisionBoxesEvent` (empty handler) is
 * deleted. `PlayerSleepInBedEvent` is `CanPlayerSleepEvent`; `LivingHurtEvent`
 * is `LivingIncomingDamageEvent`; `LivingDamageEvent` is `LivingDamageEvent.Pre`
 * (not cancellable: the damage is zeroed); the `InitGuiEvent` button +
 * `ActionPerformedEvent` pair is one `ScreenEvent.Init.Post` button whose
 * press handler runs the old action body.
 */
open class PlayerGirlEvents {
    @SubscribeEvent
    open fun onPlayerSleepInBed(event: CanPlayerSleepEvent) {
        val player = event.entity
        val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player)
        if (playerGirl != null) {
            if (player.isShiftKeyDown) {
                event.problem = Player.BedSleepingProblem.OTHER_PROBLEM
            }
        }
    }

    @SubscribeEvent
    open fun onRightClickBlockPlace(event: PlayerInteractEvent.RightClickBlock) {
        val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(event.entity.uuid)
        val clickPos = event.pos
        val world = event.entity.level()
        val player = event.entity
        if (playerGirl != null) {
            if (playerGirl.canBeInteracted()) {
                if (WorldUtils.canPlaceBlock(world, clickPos, event.hitVec.location, event.face, player)) {
                    if (playerGirl.getEntityData().get(BaseGirlEntity.IS_ANCHORED)) {
                        event.isCanceled = true
                    } else if (player.isShiftKeyDown) {
                        val blocksAround = ArrayList<BlockPos>()
                        if (world.getBlockState(clickPos.north()).isAir) {
                            blocksAround.add(clickPos.north())
                        }

                        if (world.getBlockState(clickPos.east()).isAir) {
                            blocksAround.add(clickPos.east())
                        }

                        if (world.getBlockState(clickPos.south()).isAir) {
                            blocksAround.add(clickPos.south())
                        }

                        if (world.getBlockState(clickPos.west()).isAir) {
                            blocksAround.add(clickPos.west())
                        }

                        var nearestPos: BlockPos? = null

                        for (candidatePos in blocksAround) {
                            if (nearestPos == null) {
                                nearestPos = candidatePos
                            } else {
                                val playerPos = player.position()
                                val distA = this.interpolate(
                                    candidatePos.x.toDouble(), candidatePos.y.toDouble(), candidatePos.z.toDouble(),
                                    playerPos.x, playerPos.y, playerPos.z,
                                )
                                val distB = this.interpolate(
                                    nearestPos.x.toDouble(), nearestPos.y.toDouble(), nearestPos.z.toDouble(),
                                    playerPos.x, playerPos.y, playerPos.z,
                                )
                                if (distA < distB) {
                                    nearestPos = candidatePos
                                }
                            }
                        }

                        if (nearestPos == null) {
                            player.sendSystemMessage(Component.literal("Bed is obscured"))
                        } else {
                            player.setPos(nearestPos.x + 0.5, nearestPos.y.toDouble(), nearestPos.z + 0.5)
                            if (clickPos.north() == nearestPos) {
                                player.yRot = 0.0f
                            }

                            if (clickPos.east() == nearestPos) {
                                player.yRot = 90.0f
                            }

                            if (clickPos.south() == nearestPos) {
                                player.yRot = 180.0f
                            }

                            if (clickPos.west() == nearestPos) {
                                player.yRot = -90.0f
                            }

                            if (event.level.isClientSide) {
                                HandlePlayerMovement.setMovementLock(false)
                                playerGirl.openBedsideInteractionMenu()
                            } else {
                                playerGirl.setTargetPosition(Vec3(nearestPos.x + 0.5, nearestPos.y + 0.0, nearestPos.z + 0.5))
                                playerGirl.setYawRotation(player.yRot)
                                playerGirl.getEntityData().set(BaseGirlEntity.IS_ANCHORED, true)
                                playerGirl.handleInteraction()
                            }
                        }
                    }
                }
            }
        }
    }

    open fun interpolate(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double): Double {
        val dx = x1 - x2
        val dy = y1 - y2
        val dz = z1 - z2
        return Math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    @SubscribeEvent
    open fun onPlayerRespawn(event: PlayerEvent.PlayerRespawnEvent) {
        val player: Player? = event.entity
        if (player != null) {
            val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByOwner(player.uuid)
            if (playerGirl != null) {
                val respawnPos = player.position()
                val targetLevel = player.level()
                if (playerGirl.level() !== targetLevel && targetLevel is ServerLevel) {
                    // 1.12 `playerGirl.dimension = player.dimension` + setPositionAndUpdate: 1.21 moves entities
                    // between levels with a dimension-changing teleport (the entity is re-created there)
                    playerGirl.teleportTo(targetLevel, respawnPos.x, respawnPos.y, respawnPos.z, emptySet(), playerGirl.yRot, playerGirl.xRot)
                } else {
                    playerGirl.teleportTo(respawnPos.x, respawnPos.y, respawnPos.z)
                }
                playerGirl.updateAITasks()
                Main.LOGGER.info(player.level().isAreaLoaded(playerGirl.blockPosition(), 2))
            }
        }
    }

    @SubscribeEvent
    open fun onRightClickBlockSlime(event: PlayerInteractEvent.RightClickBlock) {
        val player = event.entity
        val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player)
        if (playerGirl != null) {
            if (playerGirl is SlimePlayerEntity) {
                if (player.isShiftKeyDown) {
                    if (player.mainHandItem === ItemStack.EMPTY) {
                        if (!playerGirl.getEntityData().get(BaseGirlEntity.IS_ANCHORED)) {
                            if (!(player.xRot < 20.0f)) {
                                val hitVec: Vec3? = event.hitVec.location
                                if (hitVec != null) {
                                    val targetPos = Vec3(hitVec.x, Math.floor(hitVec.y) + 0.0, hitVec.z)
                                    if (!(hitVec.distanceTo(player.position()) > 3.0)) {
                                        player.setPos(targetPos.x, Math.floor(hitVec.y), targetPos.z)
                                        playerGirl.setTargetPosition(targetPos)
                                        playerGirl.setYawRotation(player.yRot)
                                        playerGirl.getEntityData().set(BaseGirlEntity.IS_ANCHORED, true)
                                        playerGirl.getEntityData().set(BaseGirlEntity.OUTFIT_INDEX, 0)
                                        playerGirl.setCurrentAction(Action.STARTDOGGY)
                                        if (event.level.isClientSide && ClientHooks.localPlayerUUID() == player.uuid) {
                                            HandlePlayerMovement.setMovementLock(false)
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

    @SubscribeEvent
    open fun onLivingHurt(event: LivingIncomingDamageEvent) {
        val entity = event.entity
        if (entity is Player) {
            if (event.source.`is`(DamageTypes.FALL)) {
                val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(entity)
                if (playerGirl != null) {
                    if (playerGirl is AlliePlayerEntity || playerGirl is BeePlayerEntity) {
                        event.isCanceled = true
                    }
                }
            }
        }
    }

    @SubscribeEvent
    open fun onLivingDamage(event: LivingDamageEvent.Pre) {
        if (event.source.`is`(DamageTypes.FALL)) {
            val entity = event.entity
            if (entity is Player) {
                val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(entity.uuid)
                if (playerGirl != null) {
                    if (playerGirl is SlimePlayerEntity) {
                        // 1.12: setResult(DENY) + setAmount(0) + setCanceled(true)
                        event.newDamage = 0.0f
                    }
                }
            }
        }
    }

    /** Client-only handlers (1.12 `@SideOnly(CLIENT)`); registered by `ForgeEventHandler.registerAll(true)`. */
    object ClientEvents {
        @SubscribeEvent
        fun onEntityInteractOpenMenu(event: PlayerInteractEvent.EntityInteract) {
            val target = event.target
            if (target is Player) {
                if (!event.entity.isShiftKeyDown) {
                    val clientPlayer = Minecraft.getInstance().player ?: return
                    if (event.entity.uuid == clientPlayer.uuid) {
                        val clientPlayerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(clientPlayer.uuid)
                        val targetGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(target)
                        if (targetGirl != null) {
                            if (clientPlayerGirl != null) {
                                clientPlayer.displayClientMessage(Component.literal("no lesbo yet owo"), true)
                            } else if (targetGirl.isPlayerGirl()) {
                                if (targetGirl.canOpenInteractionMenu()) {
                                    targetGirl.openInteractionMenu(clientPlayer)
                                }
                            }
                        }
                    }
                }
            }
        }

        @SubscribeEvent
        fun onEntityInteractLesbo(event: PlayerInteractEvent.EntityInteract) {
            val target = event.target
            if (target is Player) {
                val clientPlayer = Minecraft.getInstance().player ?: return
                if (event.entity.uuid == clientPlayer.uuid) {
                    val clientPlayerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(clientPlayer.uuid)
                    if (clientPlayerGirl != null) {
                        val targetGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(target.uuid)
                        if (targetGirl != null) {
                            target.displayClientMessage(Component.literal("no lesbo yet owo"), true)
                        } else {
                            if (clientPlayerGirl.canOpenInteractionMenu()) {
                                clientPlayerGirl.firstInteraction = false
                                clientPlayerGirl.openInteractionMenu(target)
                            }
                        }
                    }
                }
            }
        }

        /** 1.12 `InitGuiEvent`: adds the dressup/strip button to the survival and creative inventories. */
        @SubscribeEvent
        fun onInitGui(event: ScreenEvent.Init.Post) {
            val gui = event.screen
            if (gui is InventoryScreen || gui is CreativeModeInventoryScreen) {
                val player = Minecraft.getInstance().player
                if (player != null) {
                    val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player)
                    if (playerGirl != null) {
                        if (!playerGirl.blocksOutfitToggle()) {
                            val label = I18n.get(if (playerGirl.getOutfitIndex() == 0) "action.names.dressup" else "action.names.strip")
                            event.addListener(
                                Button.builder(Component.literal(label)) { onActionPerformed() }
                                    .bounds((gui.width * 0.5 - 35.0).toInt(), (gui.height * 0.87).toInt(), 70, 20)
                                    .build(),
                            )
                        }
                    }
                }
            }
        }

        /** 1.12 `ActionPerformedEvent` for button [eventCooldown]: starts the strip animation. */
        @JvmStatic
        fun onActionPerformed() {
            val minecraft = Minecraft.getInstance()
            val localPlayer = minecraft.player ?: return
            val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(localPlayer.uuid)
            if (playerGirl != null) {
                if (!playerGirl.blocksOutfitToggle()) {
                    if (playerGirl.getInteractionPlayerUUID() == null) {
                        if (playerGirl.getCurrentAction() === Action.NULL) {
                            minecraft.options.cameraType = CameraType.THIRD_PERSON_FRONT
                            minecraft.gameRenderer.checkEntityPostEffect(null)
                            playerGirl.setCurrentAction(Action.STRIP)
                            HandlePlayerMovement.setMovementLock(false)
                            localPlayer.closeContainer()
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val eventCooldown: Int = 284453
    }
}
