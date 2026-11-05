package com.trolmastercard.sexmod.registry

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.potion.HornyPotion
import net.minecraft.core.registries.Registries
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.Potion
import net.minecraft.world.item.alchemy.Potions
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

/**
 * Mob effect + potion registry (1.12 `HornyPotion.register()`).
 *
 * - effect `sexmod:horny_potion` ([HornyPotion], beneficial, colour 16736968)
 *   -> lang `effect.sexmod.horny_potion`
 * - potion `sexmod:horny_potion` (horny 3600 ticks + nausea 200 ticks amplifier 1),
 *   base name `horny_potion` -> lang `item.minecraft.<potion|splash_potion|lingering_potion|tipped_arrow>.effect.horny_potion`
 * - brewing: mundane + poppy (1.12 red flower meta 0) -> horny potion
 */
object ModEffects {
    @JvmField
    val MOB_EFFECTS: DeferredRegister<MobEffect> = DeferredRegister.create(Registries.MOB_EFFECT, Main.MOD_ID)

    @JvmField
    val POTIONS: DeferredRegister<Potion> = DeferredRegister.create(Registries.POTION, Main.MOD_ID)

    @JvmField
    val HORNY_POTION: DeferredHolder<MobEffect, HornyPotion> = MOB_EFFECTS.register("horny_potion", Supplier { HornyPotion() })

    @JvmField
    val HORNY_POTION_TYPE: DeferredHolder<Potion, Potion> = POTIONS.register("horny_potion", Supplier {
        Potion("horny_potion", MobEffectInstance(HORNY_POTION, 3600), MobEffectInstance(MobEffects.CONFUSION, 200, 1))
    })

    @JvmStatic
    fun register(bus: IEventBus) {
        MOB_EFFECTS.register(bus)
        POTIONS.register(bus)
        NeoForge.EVENT_BUS.addListener(RegisterBrewingRecipesEvent::class.java) { e -> onRegisterBrewing(e) }
    }

    @JvmStatic
    fun onRegisterBrewing(event: RegisterBrewingRecipesEvent) {
        event.builder.addMix(Potions.MUNDANE, Items.POPPY, HORNY_POTION_TYPE)
    }
}
