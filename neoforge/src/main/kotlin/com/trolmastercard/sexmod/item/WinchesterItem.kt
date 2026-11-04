package com.trolmastercard.sexmod.item

import com.trolmastercard.sexmod.registry.ModItems
import net.minecraft.world.item.Item
import software.bernie.geckolib.animatable.GeoItem
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.util.GeckoLibUtil

/**
 * <b>Role.</b> The Winchester — a placeholder GeckoLib-animated item (registered
 * as `sexmod:winchester`) with no gameplay logic yet.
 *
 * Port notes: 1.12 never attached a renderer (`SummonItemRenderer` exists but
 * was never set as its TEISR), so no `createGeoRenderer` is provided either.
 * Registered (1.21 items cannot exist unregistered) but kept out of the
 * creative tab; it has no event handlers and is not put on the event bus.
 */
class WinchesterItem : Item(Properties()), GeoItem {
    private val animationFactory: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = this.animationFactory

    companion object {
        @JvmStatic
        val WINCHESTER_ITEM: WinchesterItem
            get() = ModItems.WINCHESTER_ITEM.get()
    }
}
