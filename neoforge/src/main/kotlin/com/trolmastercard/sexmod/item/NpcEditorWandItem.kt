package com.trolmastercard.sexmod.item

import com.trolmastercard.sexmod.client.gui.ClothingScreen
import com.trolmastercard.sexmod.entity.AbstractPlayerGirlEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.NpcType
import com.trolmastercard.sexmod.registry.ModItems
import com.trolmastercard.sexmod.reskin.ReskinBrowserScreen
import com.trolmastercard.sexmod.util.ItemNbt
import com.trolmastercard.sexmod.util.ServerWhitelistManager
import com.trolmastercard.sexmod.util.ThreadNames
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.EntityHitResult
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import java.util.Locale

/**
 * **Role.** NPC editor wand — the customization tool. Right-clicking a girl
 * opens the reskin browser (formerly the [ClothingScreen] model-code editor);
 * attacking a girl or left-clicking copies her model-code + part-ids to the
 * clipboard (as `code$parts`); while held, its variant switches the held model
 * between normal/active (`applyEditor`).
 *
 * 1.21 port: the variant (1.12 item damage) is `ItemNbt.setMeta` (custom model
 * data, item property `sexmod:active` registered in `ClientProxy`). The event
 * handlers live in the [Events] object (registered by `ForgeEventHandler`);
 * client-only work (hit result, screen) is in the nested [Client] object.
 * Registration/model hooks (`register`, `registerItems`, `onModelRegistry`) are
 * replaced by `ModItems` and the item model JSON.
 */
open class NpcEditorWandItem : Item(Properties().stacksTo(1)) {
    override fun inventoryTick(stack: ItemStack, world: Level, entity: Entity, slot: Int, selected: Boolean) {
        if (world.isClientSide) {
            this.applyEditor(entity, stack)
        }

        super.inventoryTick(stack, world, entity, slot, selected)
    }

    /** CLIENT only. */
    open fun applyEditor(entity: Entity, stack: ItemStack) {
        if (entity is Player) {
            if (stack !== entity.mainHandItem && stack !== entity.offhandItem) {
                if (ItemNbt.getMeta(stack) != 0) ItemNbt.setMeta(stack, 0)
            } else {
                val meta = if (Client.isLookingAtGirl()) 1 else 0
                if (ItemNbt.getMeta(stack) != meta) ItemNbt.setMeta(stack, meta)
            }
        }
    }

    object Events {
        @SubscribeEvent
        fun onEntityInteract(event: PlayerInteractEvent.EntityInteract) {
            val target = event.target
            if (target is BaseGirlEntity) {
                if (BaseGirlEntity.isValidGirl(target)) {
                    val player = event.entity
                    var stack = player.mainHandItem
                    if (stack.item !== EDITOR_WAND) {
                        stack = player.offhandItem
                    }

                    if (stack.item === EDITOR_WAND) {
                        event.isCanceled = true
                        if (event.level.isClientSide) {
                            if (ServerWhitelistManager.isGlobalRenderingDisabled) {
                                ServerWhitelistManager.isGlobalRenderingDisabled = 0 != ServerWhitelistManager.getModelCount(true)
                                if (ServerWhitelistManager.isGlobalRenderingDisabled) {
                                    return
                                }
                            }

                            // Wand -> girl: open the reskin browser (or the download
                            // prompt when no packs are installed), pre-filtered to the
                            // clicked girl's type.
                            var girlFilter: String? = null
                            val npcType = NpcType.getNpcType(target)
                            if (npcType != null) {
                                girlFilter = npcType.name.lowercase(Locale.ROOT)
                            }
                            Client.showReskinBrowser(girlFilter)
                        }
                    }
                }
            }
        }

        @SubscribeEvent
        fun onAttackEntity(event: AttackEntityEvent) {
            val target = event.target
            if (target is BaseGirlEntity) {
                val player = event.entity
                var stack = player.mainHandItem
                if (stack.item !== EDITOR_WAND) {
                    stack = player.offhandItem
                }

                if (stack.item === EDITOR_WAND) {
                    event.isCanceled = true
                    if (player.level().isClientSide) {
                        val code = target.getCustomModelCode()
                        val parts = BaseGirlEntity.encodePartIdList(BaseGirlEntity.getAllPartIdsForGirl(target.getGirlId()))
                        player.sendSystemMessage(
                            Component.literal(String.format("%s's model-code: %s%s$%s", target.getDisplayNameText(), ChatFormatting.YELLOW, code, parts))
                        )
                        player.sendSystemMessage(Component.literal(ChatFormatting.ITALIC.toString() + "copied to clipboard"))
                        ThreadNames.copyToClipboard(String.format("%s$%s", code, parts))
                    }
                }
            }
        }

        @SubscribeEvent
        fun onLeftClickBlock(event: PlayerInteractEvent.LeftClickBlock) {
            if (canEdit(event.entity, event.level)) {
                event.isCanceled = true
            }
        }

        @SubscribeEvent
        fun onLeftClickEmpty(event: PlayerInteractEvent.LeftClickEmpty) {
            canEdit(event.entity, event.level)
        }
    }

    /** Client-only half (PORTING.md §2.3 rule C). */
    private object Client {
        fun isLookingAtGirl(): Boolean {
            val rayTrace = Minecraft.getInstance().hitResult
            return rayTrace is EntityHitResult && BaseGirlEntity.isValidGirl(rayTrace.entity)
        }

        fun showReskinBrowser(girlFilter: String?) {
            ReskinBrowserScreen.show(Minecraft.getInstance(), girlFilter)
        }
    }

    companion object {
        @JvmStatic
        val EDITOR_WAND: NpcEditorWandItem
            get() = ModItems.EDITOR_WAND.get()

        @JvmStatic
        fun canEdit(player: Player?, world: Level): Boolean {
            if (player == null) {
                return false
            }

            var stack = player.mainHandItem
            if (stack.item !== EDITOR_WAND) {
                stack = player.offhandItem
            }

            if (stack.item !== EDITOR_WAND) {
                return false
            } else if (!world.isClientSide) {
                return true
            } else {
                val playerGirl = AbstractPlayerGirlEntity.getPlayerGirlByUUID(player.uuid)
                if (playerGirl == null) {
                    player.displayClientMessage(Component.literal("you gotta turn into the girl, you want to copy the model-code off"), true)
                    return true
                } else {
                    val code = playerGirl.getCustomModelCode()
                    val parts = BaseGirlEntity.encodePartIdList(BaseGirlEntity.getAllPartIdsForGirl(playerGirl.getGirlId()))
                    player.sendSystemMessage(
                        Component.literal(
                            String.format("%s's model-code: %s%s$%s", ThreadNames.capitalizeFirst(NpcType.getNpcType(playerGirl).toString()), ChatFormatting.YELLOW, code, parts)
                        )
                    )
                    player.sendSystemMessage(Component.literal(ChatFormatting.ITALIC.toString() + "copied to clipboard"))
                    ThreadNames.copyToClipboard(String.format("%s$%s", code, parts))
                    return true
                }
            }
        }
    }
}
