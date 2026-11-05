package com.trolmastercard.sexmod.item

import com.trolmastercard.sexmod.client.renderer.KoboldEggItemRenderer
import com.trolmastercard.sexmod.entity.EyeAndKoboldColor
import com.trolmastercard.sexmod.entity.KoboldEggEntity
import com.trolmastercard.sexmod.registry.ModItems
import com.trolmastercard.sexmod.util.ItemNbt
import java.util.UUID
import java.util.function.Consumer
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.world.item.Item
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import software.bernie.geckolib.animatable.GeoItem
import software.bernie.geckolib.animatable.client.GeoRenderProvider
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.util.GeckoLibUtil

/**
 * <b>Role.</b> Kobold egg — placing it spawns a [KoboldEggEntity] colored
 * by the item's metadata (wool color); a `tribeID` NBT tag (written by
 * `SendEggPacket`) binds the hatched kobold to that tribe.
 *
 * 1.21: the 1.12 item metadata is [ItemNbt.getMeta] (custom model data); the
 * registry, model and renderer hookup moved to `ModItems` / `ClientProxy`
 * (via [createGeoRenderer]); the static right-click handler lives in [Events].
 */
class KoboldEggItem : Item(Properties().stacksTo(1)), GeoItem {
    private val animationFactory: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = this.animationFactory

    override fun createGeoRenderer(consumer: Consumer<GeoRenderProvider>) {
        consumer.accept(object : GeoRenderProvider {
            private var renderer: KoboldEggItemRenderer? = null

            override fun getGeoItemRenderer(): BlockEntityWithoutLevelRenderer =
                renderer ?: KoboldEggItemRenderer().also { renderer = it }
        })
    }

    /** The 1.12 static `@SubscribeEvent` methods (registered by `ForgeEventHandler`). */
    object Events {
        @SubscribeEvent
        fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
            val world = event.level
            val stack = event.itemStack
            val hitVec = event.hitVec.location
            if (!world.isClientSide) {
                if (stack.item === KOBOLD_EGG_ITEM) {
                    val egg = KoboldEggEntity(world)
                    egg.setPos(hitVec.x, hitVec.y, hitVec.z)
                    egg.getEntityData().set(KoboldEggEntity.EGG_COLOR, EyeAndKoboldColor.getColorByWoolId(ItemNbt.getMeta(stack)).toString())
                    val tag = ItemNbt.getTag(stack)
                    if (tag != null) {
                        val tribeIdString = tag.getString("tribeID")
                        if ("" != tribeIdString) {
                            egg.tribeId = UUID.fromString(tribeIdString)
                        }
                    }

                    world.addFreshEntity(egg)
                    stack.shrink(1)
                }
            }
        }
    }

    companion object {
        @JvmStatic
        val KOBOLD_EGG_ITEM: KoboldEggItem
            get() = ModItems.KOBOLD_EGG_ITEM.get()
    }
}
