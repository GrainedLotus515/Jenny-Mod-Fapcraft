package com.trolmastercard.sexmod.client.gui

import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.GirlCameraHelper
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.NpcType
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.UpdatePlayerModelPacket
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.LightTexture
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

/**
 * Girl-model selection screen (the "pick a girl" GUI): cycles through previews
 * of every non-NPC girl type plus the player's own model, and sends the chosen
 * type to the server via [UpdatePlayerModelPacket] — this is the
 * horny-potion girl transformation picker.
 *
 * **Preview construction.** For each [NpcType] (skipping
 * `isNpcOnly`) a [BaseGirlEntity] is constructed via reflection
 * into the client world, marked locally registered, and given the player's
 * custom part list; previews rotate in a static spin animation.
 *
 * CLIENT-side only. On "pick", the eye height and flying capability of the
 * local player are restored to defaults before the screen closes.
 *
 * 1.21 port: buttons are rebuilt every frame (as in 1.12) with [Button.builder];
 * [renderEntityPreview] takes the [GuiGraphics] and follows the vanilla
 * inventory preview (`EntityRenderDispatcher.render` into the GUI buffer).
 * 1.12 drew no background.
 */
open class GirlScreenBase(modelCodes: HashMap<NpcType, String>) : Screen(Component.empty()) {
    @JvmField
    var nearbyEntities: MutableList<LivingEntity> = ArrayList()

    @JvmField
    var renderIndex: Int = 0

    init {
        this.minecraft = Minecraft.getInstance()
        val mc = Minecraft.getInstance()

        for (npcType in NpcType.entries) {
            if (!npcType.isNpcOnly) {
                try {
                    val constructor = npcType.npcClass.getConstructor(Level::class.java)
                    val entity = constructor.newInstance(mc.level)
                    entity.setLocallyRegistered(true)
                    this.nearbyEntities.add(entity)
                    val parts = modelCodes[npcType]
                    if (parts != null) {
                        entity.setCustomPartList(BaseGirlEntity.decodePartIdList(parts))
                    }
                } catch (e: Exception) {
                    Main.LOGGER.error("could not decode the girl's custom parts", e)
                }
            }
        }

        mc.player?.let { this.nearbyEntities.add(it) }
    }

    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks)
        this.clearWidgets()
        if (this.nearbyEntities.isEmpty()) return
        renderEntityPreview(guiGraphics, this.width / 2, this.height / 2 + 20, 30, this.nearbyEntities[this.renderIndex])
        this.addRenderableWidget(Button.builder(Component.literal(">")) { this.actionPerformed(1, ">") }.bounds(this.width / 2 + 30, this.height / 2 - 10, 20, 20).build())
        this.addRenderableWidget(Button.builder(Component.literal("<")) { this.actionPerformed(2, "<") }.bounds(this.width / 2 - 50, this.height / 2 - 10, 20, 20).build())
        this.addRenderableWidget(Button.builder(Component.literal("pick")) { this.actionPerformed(0, "pick") }.bounds(this.width / 2 - 30, this.height / 2 + 30, 60, 20).build())
    }

    /**
     * "Pick" commits the currently previewed type: sends
     * [UpdatePlayerModelPacket] to the server and restores the player's
     * eye height and flight capability. "<"/">" cycle the preview index
     * (wrapping).
     */
    open fun actionPerformed(buttonId: Int, displayString: String) {
        if (">" == displayString && ++this.renderIndex >= this.nearbyEntities.size) {
            this.renderIndex = 0
        }

        if ("<" == displayString && --this.renderIndex < 0) {
            this.renderIndex = this.nearbyEntities.size - 1
        }

        if (buttonId == 0) {
            PacketHandler.networkWrapper.sendToServer(UpdatePlayerModelPacket(NpcType.getNpcType(this.nearbyEntities[this.renderIndex])))
            val player = Minecraft.getInstance().player ?: return
            player.closeContainer()
            // 1.12 player.eyeHeight = getDefaultEyeHeight(): eye height comes from the dimensions in 1.21
            player.refreshDimensions()
            if (!player.abilities.mayfly) {
                player.abilities.mayfly = player.abilities.instabuild
            }
        }
    }

    override fun isPauseScreen(): Boolean = false

    /**
     * Drops the throw-away preview girls from the registry. They were registered
     * via `setLocallyRegistered(true)` purely so the previews render; left
     * behind they leak and can be resolved as live girls by scene lookups.
     * The local player entry in [nearbyEntities] is not a preview.
     */
    override fun removed() {
        super.removed()

        for (entity in this.nearbyEntities) {
            if (entity is BaseGirlEntity) {
                entity.setLocallyRegistered(false)
            }
        }
    }

    companion object {
        @JvmField
        var progress: Float = 0.0f

        /**
         * Renders an entity as a rotating inventory-style preview at the given
         * screen position. Temporarily overrides the entity's yaw/pitch/head
         * rotation and (for non-players) its position, renders via the render
         * dispatcher with the spin angle accumulated from real elapsed time, then
         * restores every overridden field. The static `progress` makes all
         * previews rotate in sync.
         */
        @JvmStatic
        fun renderEntityPreview(guiGraphics: GuiGraphics, x: Int, y: Int, scale: Int, entity: LivingEntity) {
            val renderYawOffset = entity.yBodyRot
            val rotationYaw = entity.yRot
            val rotationPitch = entity.xRot
            val prevRotationYawHead = entity.yHeadRotO
            val rotationYawHead = entity.yHeadRot
            if (entity !is Player) {
                entity.setPosRaw(0.0, 0.0, 0.0)
            }

            entity.yBodyRot = 0.0f
            entity.yRot = 0.0f
            entity.xRot = 0.0f
            entity.yHeadRotO = 0.0f
            entity.yHeadRot = 0.0f
            var fps = Minecraft.getInstance().fps.toFloat()
            if (fps == 0.0f) {
                fps = 0.1f
            }

            progress += 60.0f / fps
            val pose = guiGraphics.pose()
            pose.pushPose()
            pose.translate(x.toFloat(), y.toFloat(), 50.0f)
            pose.scale(-scale.toFloat(), scale.toFloat(), scale.toFloat())
            pose.mulPose(Axis.ZP.rotationDegrees(180.0f))
            pose.mulPose(Axis.YP.rotationDegrees(135.0f))
            Lighting.setupForEntityInInventory()
            pose.mulPose(Axis.YP.rotationDegrees(-135.0f))
            pose.mulPose(Axis.YP.rotationDegrees(progress))
            val renderManager = Minecraft.getInstance().entityRenderDispatcher
            renderManager.setRenderShadow(false)
            GirlCameraHelper.beginPreview()
            try {
                RenderSystem.runAsFancy {
                    renderManager.render(entity, 0.0, 0.0, 0.0, 0.0f, GirlCameraHelper.PREVIEW_PARTIAL_TICK, pose, guiGraphics.bufferSource(), LightTexture.FULL_BRIGHT)
                }
                guiGraphics.flush()
            } finally {
                GirlCameraHelper.endPreview()
            }
            renderManager.setRenderShadow(true)
            pose.popPose()
            Lighting.setupFor3DItems()
            entity.yBodyRot = renderYawOffset
            entity.yRot = rotationYaw
            entity.xRot = rotationPitch
            entity.yHeadRotO = prevRotationYawHead
            entity.yHeadRot = rotationYawHead
        }
    }
}
