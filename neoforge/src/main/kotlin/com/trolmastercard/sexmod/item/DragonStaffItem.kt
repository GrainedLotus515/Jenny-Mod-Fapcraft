package com.trolmastercard.sexmod.item

import com.trolmastercard.sexmod.client.gui.StructureCommandScreen
import com.trolmastercard.sexmod.client.renderer.DragonStaffRenderer
import com.trolmastercard.sexmod.entity.KoboldEntity
import com.trolmastercard.sexmod.networking.GetTribeUiValuesPacket
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.registry.ModItems
import java.util.function.Consumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.ChestBlock
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import software.bernie.geckolib.animatable.GeoItem
import software.bernie.geckolib.animatable.client.GeoRenderProvider
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.animation.AnimatableManager
import software.bernie.geckolib.util.GeckoLibUtil

/**
 * <b>Role.</b> The dragon staff — the tribe-command tool. Right-clicking opens
 * the `StructureCommandScreen` (requesting tribe UI values via
 * [GetTribeUiValuesPacket]); right-clicking a bed/chest while holding it
 * is blocked so tribe blocks can only be managed through the staff UI. The
 * in-hand model and markers are handled by [DragonStaffRenderer] and
 * `StructureMarkerRenderer`. Right-click on air/block never places —
 * [use] always fails.
 *
 * Port notes: the registry/model/TEISR hookup moved to `ModItems` /
 * `ClientProxy` ([createGeoRenderer]); creative tab membership is in
 * `ModCreativeTabs`. The 1.12 hub registered the item instance, which has no
 * instance `@SubscribeEvent` methods; 1.21 `EventBus.register` throws for
 * such objects, so no `Events` object exists (see the S5 report).
 */
class DragonStaffItem : Item(Properties().stacksTo(1)), GeoItem {
    private val animationFactory: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    override fun use(world: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> =
        InteractionResultHolder.fail(player.getItemInHand(hand))

    override fun registerControllers(controllers: AnimatableManager.ControllerRegistrar) {
    }

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = this.animationFactory

    override fun createGeoRenderer(consumer: Consumer<GeoRenderProvider>) {
        consumer.accept(object : GeoRenderProvider {
            private var renderer: DragonStaffRenderer? = null

            override fun getGeoItemRenderer(): BlockEntityWithoutLevelRenderer =
                renderer ?: DragonStaffRenderer().also { renderer = it }
        })
    }

    open class RightClickHandler {
        @SubscribeEvent
        open fun onRightClickItem(event: PlayerInteractEvent.RightClickItem) {
            val world = event.level
            if (world.isClientSide) {
                val player = event.entity
                if (player.getItemInHand(InteractionHand.MAIN_HAND).item === DRAGON_STAFF ||
                    player.getItemInHand(InteractionHand.OFF_HAND).item === DRAGON_STAFF
                ) {
                    if (KoboldEntity.tribeMembers.isNotEmpty()) {
                        this.openStructureCommand()
                    }
                }
            }
        }

        /** CLIENT. */
        fun openStructureCommand() {
            Client.openStructureCommand()
        }

        @SubscribeEvent
        open fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
            val player = event.entity
            if (player.getItemInHand(InteractionHand.MAIN_HAND).item === DRAGON_STAFF ||
                player.getItemInHand(InteractionHand.OFF_HAND).item === DRAGON_STAFF
            ) {
                val block = event.level.getBlockState(event.pos).block
                // 1.12 cancelled with FAIL and still fell through to the item's right-click (opening the
                // tribe GUI). 1.21 stops at FAIL, so PASS keeps the bed/chest closed and lets the item use run.
                if (block is BedBlock) {
                    event.cancellationResult = InteractionResult.PASS
                    event.isCanceled = true
                }

                if (block is ChestBlock) {
                    event.cancellationResult = InteractionResult.PASS
                    event.isCanceled = true
                }
            }
        }

        /** Client-only half (PORTING.md §2.3 Rule C). */
        private object Client {
            fun openStructureCommand() {
                Minecraft.getInstance().setScreen(StructureCommandScreen())
                PacketHandler.networkWrapper.sendToServer(GetTribeUiValuesPacket())
            }
        }
    }

    companion object {
        @JvmStatic
        val DRAGON_STAFF: DragonStaffItem
            get() = ModItems.DRAGON_STAFF.get()
    }
}
