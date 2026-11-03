package com.trolmastercard.sexmod.registry

import com.trolmastercard.sexmod.Main
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.ItemStack
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

/**
 * Creative tabs: `sexmod:main` ("Fapcraft", allies lamp icon) with every item
 * except the winchester, plus the spawn eggs. The spawn eggs are also added to
 * the vanilla spawn-egg tab.
 */
object ModCreativeTabs {
    @JvmField
    val TABS: DeferredRegister<CreativeModeTab> = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Main.MOD_ID)

    @JvmField
    val MAIN: DeferredHolder<CreativeModeTab, CreativeModeTab> = TABS.register("main", Supplier {
        CreativeModeTab.builder()
            .title(Component.literal("Fapcraft"))
            .icon { ItemStack(ModItems.ALLIES_LAMP.get()) }
            .displayItems { _, output ->
                output.accept(ModItems.ALLIES_LAMP.get())
                output.accept(ModItems.DRAGON_STAFF.get())
                output.accept(ModItems.TRIBE_EGG.get())
                output.accept(ModItems.GALATH_COIN.get())
                output.accept(ModItems.EDITOR_WAND.get())
                output.accept(ModItems.KOBOLD_EGG_ITEM.get())
                output.accept(ModItems.LUNA_ROD.get())
                for (egg in ModItems.SPAWN_EGGS) {
                    output.accept(egg.get())
                }
            }
            .build()
    })

    @JvmStatic
    fun register(bus: IEventBus) {
        TABS.register(bus)
        bus.addListener(BuildCreativeModeTabContentsEvent::class.java) { e -> onBuildContents(e) }
    }

    @JvmStatic
    fun onBuildContents(event: BuildCreativeModeTabContentsEvent) {
        if (event.tabKey == CreativeModeTabs.SPAWN_EGGS) {
            for (egg in ModItems.SPAWN_EGGS) {
                event.accept(egg.get())
            }
        }
    }
}
