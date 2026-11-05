package com.trolmastercard.sexmod.registry

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.item.AlliesLampItem
import com.trolmastercard.sexmod.item.DragonStaffItem
import com.trolmastercard.sexmod.item.GalathCoinItem
import com.trolmastercard.sexmod.item.KoboldEggItem
import com.trolmastercard.sexmod.item.LunaRodItem
import com.trolmastercard.sexmod.item.NpcEditorWandItem
import com.trolmastercard.sexmod.item.TribeEggItem
import com.trolmastercard.sexmod.item.WinchesterItem
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.Item
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.DeferredSpawnEggItem
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredItem
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

/**
 * Item registry (replaces the 1.12 per-item `register()` + `RegistryEvent.Register<Item>`).
 *
 * Item classes have a no-argument constructor that passes their own
 * `Item.Properties`. `winchester` was never registered in 1.12 but 1.21 items
 * cannot exist unregistered, so it is registered and left out of the creative
 * tab. Spawn eggs are `<id>_spawn_egg` for the 9 entities that had egg colours.
 */
object ModItems {
    @JvmField
    val ITEMS: DeferredRegister.Items = DeferredRegister.createItems(Main.MOD_ID)

    @JvmField val ALLIES_LAMP: DeferredItem<AlliesLampItem> = ITEMS.register("allies_lamp", Supplier { AlliesLampItem() })
    @JvmField val DRAGON_STAFF: DeferredItem<DragonStaffItem> = ITEMS.register("dragon_staff", Supplier { DragonStaffItem() })
    @JvmField val TRIBE_EGG: DeferredItem<TribeEggItem> = ITEMS.register("tribe_egg", Supplier { TribeEggItem() })
    @JvmField val GALATH_COIN: DeferredItem<GalathCoinItem> = ITEMS.register("galath_coin", Supplier { GalathCoinItem() })
    @JvmField val EDITOR_WAND: DeferredItem<NpcEditorWandItem> = ITEMS.register("npc_editor_wand", Supplier { NpcEditorWandItem() })
    @JvmField val KOBOLD_EGG_ITEM: DeferredItem<KoboldEggItem> = ITEMS.register("kobold_egg_item", Supplier { KoboldEggItem() })
    @JvmField val LUNA_ROD: DeferredItem<LunaRodItem> = ITEMS.register("luna_rod", Supplier { LunaRodItem() })
    @JvmField val WINCHESTER_ITEM: DeferredItem<WinchesterItem> = ITEMS.register("winchester", Supplier { WinchesterItem() })

    /** Aliases by registry ID. */
    @JvmField val NPC_EDITOR_WAND: DeferredItem<NpcEditorWandItem> = EDITOR_WAND
    @JvmField val WINCHESTER: DeferredItem<WinchesterItem> = WINCHESTER_ITEM

    private fun <T : Mob> spawnEgg(
        entityId: String,
        type: DeferredHolder<EntityType<*>, EntityType<T>>,
        background: Int,
        highlight: Int,
    ): DeferredItem<DeferredSpawnEggItem> =
        ITEMS.register("${entityId}_spawn_egg", Supplier { DeferredSpawnEggItem(type, background, highlight, Item.Properties()) })

    @JvmField val JENNY_SPAWN_EGG = spawnEgg("jenny", ModEntities.JENNY, 3286592, 12655237)
    @JvmField val ELLIE_SPAWN_EGG = spawnEgg("ellie", ModEntities.ELLIE, 1447446, 9961472)
    @JvmField val SLIME_SPAWN_EGG = spawnEgg("slime", ModEntities.SLIME, 13167780, 8244330)
    @JvmField val BIA_SPAWN_EGG = spawnEgg("bia", ModEntities.BIA, 7488816, 7254603)
    @JvmField val BEE_SPAWN_EGG = spawnEgg("bee", ModEntities.BEE, 16701032, 4400155)
    @JvmField val LUNA_SPAWN_EGG = spawnEgg("luna", ModEntities.LUNA, 7881787, 7940422)
    @JvmField val GOBLIN_SPAWN_EGG = spawnEgg("goblin", ModEntities.GOBLIN, 39424, 19456)
    @JvmField val GALATH_SPAWN_EGG = spawnEgg("galath", ModEntities.GALATH, 16711680, 16711680)
    @JvmField val MANGLELIE_SPAWN_EGG = spawnEgg("manglelie", ModEntities.MANGLELIE, 16382457, 8485574)

    /** All spawn eggs, in registration order (creative tabs). */
    @JvmField
    val SPAWN_EGGS: List<DeferredItem<DeferredSpawnEggItem>> = listOf(
        JENNY_SPAWN_EGG, ELLIE_SPAWN_EGG, SLIME_SPAWN_EGG, BIA_SPAWN_EGG, BEE_SPAWN_EGG,
        LUNA_SPAWN_EGG, GOBLIN_SPAWN_EGG, GALATH_SPAWN_EGG, MANGLELIE_SPAWN_EGG,
    )

    @JvmStatic
    fun register(bus: IEventBus) {
        ITEMS.register(bus)
    }
}
