package com.trolmastercard.sexmod.potion

import com.trolmastercard.sexmod.entity.ai.GirlAiBase
import com.trolmastercard.sexmod.networking.GirlDataPacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.registry.ModEffects
import net.minecraft.core.Holder
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.item.alchemy.Potion
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.tick.EntityTickEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent

/**
 * The horny potion — the item/effect that transforms the player into a girl
 * (spawns the XxxPlayerEntity form, hides the vanilla player). Girls check
 * `getEffect(HORNY_POTION)` to decide whether scenes skip
 * the PAYMENT gate (Kobold startActionFromHandState(), Jenny's menu) and to track the transformed
 * state (Jenny's `yFlag`, Luna's menu behavior).
 *
 * 1.21: the effect is registered as `sexmod:horny_potion` and the potion as
 * `sexmod:horny_potion` by `ModEffects` (with the brewing mix
 * mundane + poppy). [HORNY_POTION] / [POTION_TYPE] are the registry holders.
 */
class HornyPotion : MobEffect {
    /** Registry factory: beneficial, liquid colour 16736968 (the 1.12 static instance's values). */
    constructor() : super(MobEffectCategory.BENEFICIAL, 16736968)

    /**
     * 1.12 constructor. `name` was the registry/translation name and the icon
     * indices addressed the 1.12 inventory sheet; 1.21 derives both from the
     * registry ID (`effect.sexmod.horny_potion`, `mob_effect/horny_potion.png`).
     */
    @Suppress("UNUSED_PARAMETER")
    constructor(name: String, isBadEffect: Boolean, liquidColor: Int, iconX: Int, iconY: Int) :
        super(if (isBadEffect) MobEffectCategory.HARMFUL else MobEffectCategory.BENEFICIAL, liquidColor)

    /**
     * Event handlers (1.12 registered a `HornyPotion` instance on the event bus).
     * Registered by `ForgeEventHandler.registerAll` on the common path.
     */
    object Events {
        @SubscribeEvent
        fun onPlayerTick(event: PlayerTickEvent.Post) {
            val player = event.entity
            val effect = player.getEffect(HORNY_POTION)
            if (!player.level().isClientSide) {
                if (effect != null) {
                    if (effect.duration <= 3500) {
                        player.removeEffect(HORNY_POTION)
                        PacketHandler.networkWrapper.sendTo(GirlDataPacket(player), player as ServerPlayer)
                    }
                }
            }
        }

        /** 1.12 `LivingUpdateEvent` (both sides). */
        @SubscribeEvent
        fun onLivingUpdate(event: EntityTickEvent.Pre) {
            val entity = event.entity
            if (entity is Villager) {
                val villager = entity
                if (villager.hasEffect(HORNY_POTION)) {
                    villager.goalSelector.addGoal(2, GirlAiBase(villager))
                    villager.removeEffect(HORNY_POTION)
                }
            }

            if (entity is Animal) {
                val animal = entity
                if (animal.hasEffect(HORNY_POTION)) {
                    if (animal.age >= 0) {
                        animal.age = 0
                        animal.resetLove()
                        animal.setInLove(animal.level().getNearestPlayer(animal, 30.0))
                    }

                    animal.removeEffect(HORNY_POTION)
                }
            }
        }
    }

    companion object {
        /** The registered effect (1.12 static `HORNY_POTION`). */
        @JvmStatic
        val HORNY_POTION: Holder<MobEffect>
            get() = ModEffects.HORNY_POTION

        /** The registered potion (1.12 static `POTION_TYPE`). */
        @JvmStatic
        val POTION_TYPE: Holder<Potion>
            get() = ModEffects.HORNY_POTION_TYPE

        /** Kept for call sites; registration and the brewing mix live in `ModEffects`. */
        @JvmStatic
        fun register() {
        }
    }
}
