package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.gui.GalathScreen
import com.trolmastercard.sexmod.entity.api.IGoblin
import com.trolmastercard.sexmod.proxy.ClientProxy
import com.trolmastercard.sexmod.util.WorldUtils
import java.util.ConcurrentModificationException
import net.minecraft.client.Minecraft
import net.minecraft.world.damagesource.DamageTypes
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.InputEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent

/**
 * Goblin event glue: carries a shouldered goblin across dimension changes
 * (server clones it into the new dimension), makes owned goblins immune to
 * everything but the void, and on the client despawns goblins whose owner is
 * in another dimension and opens the gesture pad on the G key.
 *
 * 1.21 (Rule E): the client tick and key handlers live in [ClientEvents];
 * `LivingAttackEvent` is `LivingIncomingDamageEvent`.
 */
class GoblinEventHandler {
    @SubscribeEvent
    fun onPlayerChangedDimension(event: PlayerEvent.PlayerChangedDimensionEvent) {
        val player = event.entity
        val uuid = player.uuid
        val world = player.level()
        var existing: GoblinEntity? = null

        try {
            for (girl in BaseGirlEntity.getGirlEntityList()) {
                if (!girl.level().isClientSide && girl is GoblinEntity) {
                    if (uuid == girl.getOwnerUUID()) {
                        val modelCode = girl.getCustomModelCode()
                        val partCode = girl.getCustomPartListCode()
                        existing = girl
                        girl.setOwnerUUID(null)
                        girl.setInteractionPlayerUUID(null)
                        girl.setCurrentAction(Action.NULL)
                        // 1.12 set newGoblin.dimension + forceSpawn; the player's (new) level is used directly
                        val newGoblin = GoblinEntity(world)
                        newGoblin.setCustomModelCode(modelCode)
                        newGoblin.setCustomPartListCode(partCode)
                        newGoblin.getEntityData().set(GoblinEntity.TAMED, true)
                        world.addFreshEntity(newGoblin)
                        newGoblin.teleportTo(player.x, player.y, player.z)
                        newGoblin.setOwnerUUID(uuid)
                        newGoblin.setCurrentAction(Action.SHOULDER_IDLE)
                        break
                    }
                }
            }
        } catch (ex: ConcurrentModificationException) {
            // ignored: entity list mutated during iteration; retried on the next tick
        }

        if (existing != null) {
            existing.discard()
            (BaseGirlEntity.getGirlEntityList() as? MutableList<BaseGirlEntity>)?.remove(existing)
        }
    }

    /** 1.12 `LivingAttackEvent`. */
    @SubscribeEvent
    fun onLivingAttack(event: LivingIncomingDamageEvent) {
        if (!(event.source.`is`(DamageTypes.FELL_OUT_OF_WORLD) || event.source.`is`(DamageTypes.GENERIC_KILL))) {
            val living = event.entity
            if (living is GoblinEntity) {
                if (living.getOwnerUUID() != null) {
                    event.setCanceled(true)
                }
            }
        }
    }

    /** Client half (Rule E): registered by `ForgeEventHandler` on the client only. */
    object ClientEvents {
        @JvmField
        var mc: Minecraft? = null

        /** 1.12 `ClientTickEvent` with `phase != START`. */
        @SubscribeEvent
        fun onClientTick(event: ClientTickEvent.Post) {
            val goblins = ArrayList<GoblinEntity>()

            try {
                for (girl in BaseGirlEntity.getGirlEntityList()) {
                    if (girl.level().isClientSide && girl is GoblinEntity) {
                        val uuid = girl.getOwnerUUID()
                        if (uuid != null) {
                            val player = girl.level().getPlayerByUUID(uuid)
                            if (player != null && WorldUtils.getDimensionId(player.level()) != WorldUtils.getDimensionId(girl.level())) {
                                goblins.add(girl)
                            }
                        }
                    }
                }
            } catch (ex: ConcurrentModificationException) {
                // ignored: entity list mutated during iteration; retried on the next tick
            }

            for (goblin in goblins) {
                goblin.setOwnerUUID(null)
                goblin.setInteractionPlayerUUID(null)
                goblin.discard()
            }
        }

        /** 1.12 `KeyInputEvent`. */
        @SubscribeEvent
        fun onKeyInput(event: InputEvent.Key) {
            var minecraft = mc
            if (minecraft == null) {
                minecraft = Minecraft.getInstance()
                mc = minecraft
            }

            if (minecraft.screen !is GalathScreen) {
                if (ClientProxy.keyBindings[0].consumeClick()) {
                    var interactingGirl: BaseGirlEntity? = null
                    val uuid = minecraft.player?.uuid ?: return

                    try {
                        for (girl in BaseGirlEntity.getGirlEntityList()) {
                            if (girl.level().isClientSide && girl is IGoblin) {
                                if (uuid == (girl as IGoblin).getOwnerUUID()) {
                                    interactingGirl = girl
                                    break
                                }
                            }
                        }
                    } catch (ex: ConcurrentModificationException) {
                        // ignored: entity list mutated during iteration; retried on the next tick
                    }

                    if (interactingGirl != null) {
                        if (interactingGirl.getCurrentAction() == Action.SHOULDER_IDLE) {
                            minecraft.setScreen(GalathScreen(interactingGirl))
                        }
                    }
                }
            }
        }
    }
}
