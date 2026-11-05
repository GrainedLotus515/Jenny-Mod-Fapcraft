package com.trolmastercard.sexmod.registry

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.gui.ChestContainer
import com.trolmastercard.sexmod.client.gui.GirlInventoryContainer
import com.trolmastercard.sexmod.client.gui.GirlInventoryContainer2
import net.minecraft.core.registries.Registries
import net.minecraft.world.inventory.MenuType
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

/**
 * Menu types for the 1.12 `IGuiHandler` containers. Opened through
 * `GuiHandler.openGui`; the client side reads `guiId, x, y, z` from the extra data.
 *
 * - `girl_equipment` -> [ChestContainer] (gui id 0, non-Luna girls)
 * - `luna_equipment` -> [GirlInventoryContainer2] (gui id 0, Luna)
 * - `girl_chest` -> [GirlInventoryContainer] (gui id 1)
 */
object ModMenus {
    @JvmField
    val MENUS: DeferredRegister<MenuType<*>> = DeferredRegister.create(Registries.MENU, Main.MOD_ID)

    @JvmField
    val GIRL_EQUIPMENT: DeferredHolder<MenuType<*>, MenuType<ChestContainer>> = MENUS.register("girl_equipment", Supplier {
        IMenuTypeExtension.create { id, inv, buf -> ChestContainer(id, inv, buf) }
    })

    @JvmField
    val LUNA_EQUIPMENT: DeferredHolder<MenuType<*>, MenuType<GirlInventoryContainer2>> = MENUS.register("luna_equipment", Supplier {
        IMenuTypeExtension.create { id, inv, buf -> GirlInventoryContainer2(id, inv, buf) }
    })

    @JvmField
    val GIRL_CHEST: DeferredHolder<MenuType<*>, MenuType<GirlInventoryContainer>> = MENUS.register("girl_chest", Supplier {
        IMenuTypeExtension.create { id, inv, buf -> GirlInventoryContainer(id, inv, buf) }
    })

    @JvmStatic
    fun register(bus: IEventBus) {
        MENUS.register(bus)
    }
}
