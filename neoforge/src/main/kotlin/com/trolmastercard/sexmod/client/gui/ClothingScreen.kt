package com.trolmastercard.sexmod.client.gui

import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.math.Axis
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.GirlCameraHelper
import com.trolmastercard.sexmod.entity.AbstractKoboldPlayerEntity
import com.trolmastercard.sexmod.entity.AbstractNpcOnlyEntity
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BoneType
import com.trolmastercard.sexmod.entity.NpcType
import com.trolmastercard.sexmod.entity.SexSceneEntity
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.UploadModelStringPacket
import com.trolmastercard.sexmod.proxy.ClientProxy
import com.trolmastercard.sexmod.reskin.ReskinBrowserScreen
import com.trolmastercard.sexmod.util.Point2D
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.SceneDebug
import com.trolmastercard.sexmod.util.ServerWhitelistManager
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.InputEvent
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.AbstractMap.SimpleEntry
import java.util.UUID

/**
 * The girl wand's customization GUI — cycles the target girl's custom model
 * parts (bone toggles with `<`/`>` and `+`/`-`),
 * rotates the preview model, and hosts the custom-model folder/tutorial
 * buttons. Opened via [openClothingScreen] (the K key
 * while transformed, or right-clicking a girl with the wand).
 *
 * **Pitfall (crash fix):** the folder button MUST check
 * `exists()`/`mkdirs()` before `java.awt.Desktop.open()` —
 * opening the not-yet-existing `sexmod/custom_models/singleplayer`
 * directory throws `IllegalArgumentException` (NOT IOException) and
 * crashes the whole client.
 *
 * CLIENT-ONLY. 1.21 port: the draw helpers keep their 1.12 signatures and draw
 * into the [GuiGraphics] of the current frame ([currentGraphics], set at the
 * start of [render]); `setWorldAndResolution` + `initGui` are [init];
 * `mouseClickMove` is [mouseDragged]; the wheel is forwarded to the list in
 * [mouseScrolled]. The preview draws follow the vanilla inventory entity preview.
 * The decompiled `drawPartRotated(x, y, rotation, entity, scale)` parameters
 * are really (x, y, size, entity, partialTicks) and are named so here.
 * 1.12 drew no screen background.
 */
open class ClothingScreen(girl: BaseGirlEntity) : Screen(Component.empty()) {
    @JvmField
    var guiX: Int = 0

    @JvmField
    var guiY: Int = 0

    @JvmField
    var modelRotation: Float = 0.0f

    @JvmField
    var previewGirl: BaseGirlEntity

    @JvmField
    var isRendering: Boolean = false

    @JvmField
    var modelList: CustomModelList? = null

    @JvmField
    val girlId: UUID = girl.getGirlId()

    @JvmField
    var partsCount: Int = 0

    @JvmField
    var lastMouseX: Int = 0

    @JvmField
    var isEditing: Boolean = false

    @JvmField
    var scrollVelocity: Int = 0

    @JvmField
    var scrollDirection: Int = 1

    /** The graphics of the frame being drawn (1.21 replacement for the global GL state). */
    @JvmField
    var currentGraphics: GuiGraphics? = null

    init {
        this.minecraft = Minecraft.getInstance()
        val mc = Minecraft.getInstance()
        if (DUMMY_SCENE == null) {
            try {
                DUMMY_SCENE = SexSceneEntity.createSceneEntity(mc.level!!, this.girlId, BoneType.HEAD)
            } catch (t: Throwable) {
                // headless / world not ready — leave null (drawPart handles it)
            }
        }
        var npcType = NpcType.getNpcType(girl)
        if (npcType == null) {
            npcType = NpcType.JENNY
        }

        var preview: BaseGirlEntity? = null
        try {
            val constructor = npcType.npcClass.getConstructor(Level::class.java)
            preview = constructor.newInstance(mc.level)
            preview.setLocallyRegistered(true)
        } catch (e: Exception) {
            Main.LOGGER.error("could not create the clothing preview girl", e)
        }
        // java-npe: 1.12 dereferenced the preview girl unconditionally below
        this.previewGirl = preview!!

        // Copy the REAL girl's appearance DNA onto the fresh preview so the sliders
        // start at her ACTUAL values instead of the preview's default appearance.
        // Without this, every wardrobe open showed the default (3,2,2,8,8,...) DNA
        // and the slider values were never consistent with the live girl.
        try {
            if (girl is AbstractNpcOnlyEntity || girl is AbstractKoboldPlayerEntity) {
                val dna: String? = girl.getEntityData().get(AbstractNpcOnlyEntity.APPEARANCE_DNA)
                this.previewGirl.getEntityData().set(AbstractNpcOnlyEntity.APPEARANCE_DNA, dna ?: "")
            }
        } catch (t: Throwable) {
            // preview girl keeps its default appearance — display-only caveat
        }

        this.refreshCustomParts()
        val modelCode = girl.getCustomModelCode()
        this.previewGirl.getEntityData().set(BaseGirlEntity.CUSTOM_MODEL_KEY, modelCode)
        var customBoneCount = 0

        for (partName in this.previewGirl.getCustomPartsSet()) {
            val boneType = ServerWhitelistManager.getBoneType(partName)
            if (BoneType.CUSTOM_BONE == boneType) {
                customBoneCount++
            }

            var entry: MutableMap.MutableEntry<BoneType, MutableMap.MutableEntry<MutableList<String>, Int>>? = null
            if (BoneType.CUSTOM_BONE == boneType && customBoneCount > 1) {
                entry = getCustomPartData(this.previewGirl)
            } else {
                for (existingEntry in customPartEntries) {
                    if (existingEntry.key == boneType) {
                        entry = existingEntry
                    }
                }
            }

            if (entry != null) {
                customPartEntries.remove(entry)
                var index = entry.value.key.indexOf(partName)
                if (index == -1) {
                    index = 0
                }

                entry.value.setValue(index)
                customPartEntries.add(entry)
            }
        }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        this.modelList?.handleMouseInput(scrollY)
        return true
    }

    open fun refreshCustomParts() {
        customPartEntries.clear()
        val customParts = this.previewGirl.buildCustomPartsData(this.girlId)
        this.partsCount = customParts.size
        // the SAME entry objects as the preview girl's customPartsData (slider writes must stay visible)
        @Suppress("UNCHECKED_CAST")
        customPartEntries.addAll(customParts as List<MutableMap.MutableEntry<BoneType, MutableMap.MutableEntry<MutableList<String>, Int>>>)

        for (boneType in BoneType.entries) {
            if (boneType != BoneType.GIRL_SPECIFIC) {
                val parts = ArrayList<String>()
                parts.add("cross")
                customPartEntries.add(SimpleEntry(boneType, SimpleEntry<MutableList<String>, Int>(parts, 0)))
            }
        }

        for (modelParts in ServerWhitelistManager.getModelParts(this.previewGirl).entries) {
            var entry: MutableMap.MutableEntry<BoneType, MutableMap.MutableEntry<MutableList<String>, Int>>? = null

            for (existingEntry in customPartEntries) {
                if (modelParts.key == existingEntry.key) {
                    entry = existingEntry
                }
            }

            if (entry != null) {
                val index = customPartEntries.indexOf(entry)
                customPartEntries.remove(entry)
                entry.value.key.addAll(modelParts.value)
                customPartEntries.add(index, entry)
            }
        }
    }

    override fun init() {
        // 1.12 setWorldAndResolution
        this.guiX = this.screenX(76.0f)
        this.guiY = this.screenY(89.0f)
        this.modelRotation = 90.0f
        // 1.12 initGui
        this.modelList = CustomModelList(Minecraft.getInstance(), this)
    }

    open fun isMouseOverPart(mouseX: Int, mouseY: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        return if (mouseX < left) {
            false
        } else if (mouseX > right) {
            false
        } else {
            if (mouseY < top) false else mouseY <= bottom
        }
    }

    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        this.currentGraphics = guiGraphics
        super.render(guiGraphics, mouseX, mouseY, partialTicks)
        if (this.isRendering) {
            currentModelYaw = currentModelYaw + RotationHelper.lerp(targetScrollOffset.toFloat(), scrollOffset.toFloat(), partialTicks)
        }

        this.startRendering()
        val x = this.guiX - this.screenX(15.0f)
        val y = this.guiY - 20
        this.drawTexturedModalRect(x, y, 100, if (this.isMouseOverPart(mouseX, mouseY, x, y, x + 20, y + 20)) 40 else 20, 20, 20)
        if (ServerWhitelistManager.getCustomModelsKey() == null) {
            this.drawBackground(x, mouseX, mouseY)
        }

        GirlCameraHelper.beginPreview()
        try {
            this.drawPartRotated(this.guiX, this.guiY, this.modelRotation, this.previewGirl, GirlCameraHelper.PREVIEW_PARTIAL_TICK)
        } finally {
            GirlCameraHelper.endPreview()
        }
        try {
            this.previewGirl.tick()
        } catch (t: Throwable) {
            // the preview girl is not in a level; a failing tick only freezes her animation
        }
        this.modelList?.drawScreen(mouseX, mouseY, partialTicks)

        // Draw the small left button column AGAIN on top of the list so the list's
        // background can never hide these buttons (they sit at the left edge).
        val bx = this.guiX - this.screenX(15.0f)
        val by = this.guiY - 20
        this.drawTexturedModalRect(bx, by, 100, if (this.isMouseOverPart(mouseX, mouseY, bx, by, bx + 20, by + 20)) 40 else 20, 20, 20)
        if (ServerWhitelistManager.getCustomModelsKey() == null) {
            this.drawBackground(bx, mouseX, mouseY)
        }
    }

    open fun drawBackground(x: Int, mouseX: Int, mouseY: Int) {
        var y = this.guiY - 40
        this.drawTexturedModalRect(x, y, 120, if (this.isMouseOverPart(mouseX, mouseY, x, y, x + 20, y + 20)) 40 else 20, 20, 20)
        y -= 20
        this.drawTexturedModalRect(x, y, 20, if (this.isMouseOverPart(mouseX, mouseY, x, y, x + 20, y + 20)) 170 else 150, 20, 20)
        y -= 20
        this.drawTexturedModalRect(x, y, 0, if (this.isMouseOverPart(mouseX, mouseY, x, y, x + 20, y + 20)) 170 else 150, 20, 20)
        // 5th (bottom) button: opens the reskin pack browser. The sheet has NO art
        // at (40,150)/(40,170) (fully transparent in the original PNG too) — use the
        // unused "list" button plate (40,20)/(40,40) so the button is visible again.
        y -= 20
        this.drawTexturedModalRect(x, y, 40, if (this.isMouseOverPart(mouseX, mouseY, x, y, x + 20, y + 20)) 40 else 20, 20, 20)
    }

    /** 1.12 `drawTexturedModalRect` with [GUI_TEXTURE] bound. */
    open fun drawTexturedModalRect(x: Int, y: Int, u: Int, v: Int, w: Int, h: Int) {
        this.currentGraphics?.blit(GUI_TEXTURE, x, y, u, v, w, h)
    }

    override fun isPauseScreen(): Boolean = false

    open fun playClickSound() {
        val mc = Minecraft.getInstance()
        mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
        val selectedParts = HashSet<String>()
        val girlSpecific = ArrayList<Int>()

        for (entry in customPartEntries) {
            if (entry.key == BoneType.GIRL_SPECIFIC) {
                girlSpecific.add(entry.value.value)
            } else {
                val data = entry.value
                val index = data.value
                if (index != 0) {
                    val partName = data.key[index]
                    selectedParts.add(partName)
                }
            }
        }

        PacketHandler.networkWrapper.sendToServer(UploadModelStringPacket(BaseGirlEntity.encodeCustomParts(selectedParts), this.girlId, girlSpecific))
        mc.player?.closeContainer()
    }

    open fun onBoneTypeToggle(boneType: BoneType, forward: Boolean, clickIndex: Int) {
        Main.LOGGER.info(String.format("[DEBUG toggle] bone=%s forward=%s clickIndex=%d partsCount=%d mSize=%d", boneType, forward, clickIndex, this.partsCount, customPartEntries.size))
        val mc = Minecraft.getInstance()
        mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
        val matches = ArrayList<MutableMap.MutableEntry<BoneType, MutableMap.MutableEntry<MutableList<String>, Int>>>()
        val indices = ArrayList<Int>()
        var i = 0

        for (entry in customPartEntries) {
            if (entry.key == boneType) {
                matches.add(entry)
                indices.add(i)
            }

            i++
        }

        if (matches.size != 0) {
            val entry: MutableMap.MutableEntry<BoneType, MutableMap.MutableEntry<MutableList<String>, Int>>
            val index: Int
            if (matches.size == 1) {
                entry = matches[0]
                index = indices[0]
            } else {
                var partIndex: Int = if (this.partsCount != 0 && clickIndex <= this.partsCount - 1 + BoneType.getCustomBoneCount()) {
                    clickIndex
                } else {
                    clickIndex - (this.partsCount + BoneType.getCustomBoneCount())
                }

                // guard against out-of-range click indices (multi-custom-bone lists)
                partIndex = Math.max(0, Math.min(partIndex, matches.size - 1))
                entry = matches[partIndex]
                index = indices[partIndex]
            }

            val data = entry.value
            var currentIndex = data.value
            val partCount = data.key.size
            val prevName = data.key[currentIndex]
            if (forward) {
                if (++currentIndex >= partCount) {
                    currentIndex = 0
                }
            } else if (--currentIndex < 0) {
                currentIndex = partCount - 1
            }
            val nextName = data.key[currentIndex]
            Main.LOGGER.info(String.format("[DEBUG toggle result] bone=%s %s -> %s (idx %d->%d/%d)", boneType, prevName, nextName, data.value, currentIndex, partCount))
            mc.player?.sendSystemMessage(Component.literal(String.format("§e%s: %s -> %s", boneType, prevName, nextName)))
            customPartEntries[index] = SimpleEntry(entry.key, SimpleEntry(entry.value.key, currentIndex))
            // Re-sync previewGirl.customPartsData with customPartEntries's GIRL_SPECIFIC entries.
            // customPartEntries.set above replaced one entry with a NEW object; rebuilding
            // customPartsData from the current GIRL_SPECIFIC entries keeps both
            // pointing at the SAME shared entry objects, so slider writes
            // (setCustomPartValue -> customPartsData) and the confirm packet
            // (playClickSound -> customPartEntries) always read the same live values.
            val girlSpecificEntries = ArrayList<Map.Entry<BoneType, Map.Entry<List<String>, Int>>>()

            for (girlSpecificEntry in customPartEntries) {
                if (girlSpecificEntry.key == BoneType.GIRL_SPECIFIC) {
                    girlSpecificEntries.add(girlSpecificEntry)
                }
            }

            this.previewGirl.setCustomPartsData(girlSpecificEntries)
        }
    }

    open fun drawPart(x: Int, y: Int, size: Float, entity: SexSceneEntity) {
        this.drawPartRotated(x, y, size, entity, 1.876945f)
    }

    open fun drawPreviewModel(entity: SexSceneEntity) {
        this.drawPartRotatedScaled(this.guiX, this.guiY, this.modelRotation, entity, 2.876945f, if (entity.isItemModel) 1 else 0)
    }

    open fun drawHoverText(text: String, x: Int, y: Int) {
        this.currentGraphics?.renderTooltip(this.font, Component.literal(text), x, y)
    }

    override fun mouseDragged(mouseXd: Double, mouseYd: Double, mouseButton: Int, dragX: Double, dragY: Double): Boolean {
        super.mouseDragged(mouseXd, mouseYd, mouseButton, dragX, dragY)
        val mouseX = mouseXd.toInt()
        if (mouseButton == 0) {
            if (mouseX >= this.width / 2) {
                val deltaX = mouseX - this.lastMouseX
                selectedPartIds.add(deltaX)
                this.lastMouseX = mouseX
            }
        }
        return true
    }

    override fun mouseClicked(mouseXd: Double, mouseYd: Double, mouseButton: Int): Boolean {
        super.mouseClicked(mouseXd, mouseYd, mouseButton)
        val mouseX = mouseXd.toInt()
        val mouseY = mouseYd.toInt()
        val mc = Minecraft.getInstance()
        this.modelList?.mouseClicked(mouseX, mouseY, mouseButton)
        if (mouseButton == 0) {
            this.isEditing = true
            this.isRendering = true
            this.lastMouseX = mouseX
            val x = this.guiX - this.screenX(15.0f)
            var y = this.guiY - 20
            if (this.isMouseOverPart(mouseX, mouseY, x, y, x + 20, y + 20)) {
                this.playClickSound()
            }

            if (ServerWhitelistManager.getCustomModelsKey() == null) {
                y = this.guiY - 40
                if (this.isMouseOverPart(mouseX, mouseY, x, y, x + 20, y + 20)) {
                    mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
                    mc.player?.closeContainer()
                    val modelCount = ServerWhitelistManager.getModelCount(true)
                    if (modelCount != 0) {
                        ServerWhitelistManager.isGlobalRenderingDisabled = true
                    } else {
                        val girl = BaseGirlEntity.getClientGirlEntity(this.girlId)
                        if (girl != null) {
                            openClothingScreen(girl)
                        }
                    }
                } else {
                    y -= 20
                    if (this.isMouseOverPart(mouseX, mouseY, x, y, x + 20, y + 20)) {
                        val folder = File(ServerWhitelistManager.getGlobalModelOverride())
                        SceneDebug.log(SceneDebug.CLOTHING, "ClothingScreen: opening folder %s (exists=%s)", folder.absolutePath, folder.exists())
                        if (folder.exists() || folder.mkdirs()) {
                            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                                try {
                                    Desktop.getDesktop().open(folder)
                                } catch (ioe: IOException) {
                                    // ignored: no desktop handler for opening folders; the path above is logged
                                }
                            }
                        }
                    } else {
                        y -= 20
                        if (this.isMouseOverPart(mouseX, mouseY, x, y, x + 20, y + 20)) {
                            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                try {
                                    Desktop.getDesktop().browse(URI("https://web.archive.org/web/20241120012617/http://fapcraft.org/assets/video/tutorial/girl_wand.mp4"))
                                } catch (urie: URISyntaxException) {
                                    throw RuntimeException(urie)
                                } catch (ioe: IOException) {
                                    throw RuntimeException(ioe)
                                }
                            }
                        } else {
                            y -= 20
                            if (this.isMouseOverPart(mouseX, mouseY, x, y, x + 20, y + 20)) {
                                mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
                                ReskinBrowserScreen.show(mc, null)
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, mouseButton: Int): Boolean {
        super.mouseReleased(mouseX, mouseY, mouseButton)
        if (mouseButton == 0) {
            this.isRendering = false
            this.isEditing = false
        }

        this.scrollVelocity = targetScrollOffset
        return true
    }

    open fun screenX(percent: Float): Int = Math.round(this.width * (percent / 100.0f))

    open fun screenY(percent: Float): Int = Math.round(this.height * (percent / 100.0f))

    override fun removed() {
        super.removed()
        try {
            this.previewGirl.discard()
        } finally {
            this.previewGirl.setLocallyRegistered(false)
        }
        selectedPartIds.clear()
        customPartEntries.clear()
    }

    open fun getPreviewGirl(): BaseGirlEntity = this.previewGirl

    open fun drawPartBackground(x: Int, y: Int, u: Int, v: Int) {
        this.drawTexturedModalRect(x, y, u, v, 20, 20)
    }

    open fun drawPartIcon(x: Int, y: Int, u: Int) {
        this.drawPartBackground(x, y, u, 0)
    }

    open fun drawPartAt(x: Int, y: Int, pos: Point2D) {
        this.drawPartBackground(x, y, pos.x, pos.y)
    }

    open fun drawPartRotated(x: Int, y: Int, size: Float, entity: LivingEntity, partialTicks: Float) {
        this.drawPartRotatedScaled(x, y, size, entity, partialTicks, 0)
    }

    open fun drawPartRotatedScaled(x: Int, y: Int, size: Float, entity: LivingEntity, partialTicks: Float, zOffset: Int) {
        val guiGraphics = this.currentGraphics ?: return
        val renderYawOffset = entity.yBodyRot
        val rotationYaw = entity.yRot
        val rotationPitch = entity.xRot
        val prevRotationYawHead = entity.yHeadRotO
        val rotationYawHead = entity.yHeadRot
        entity.yBodyRot = 0.0f
        entity.yRot = 0.0f
        entity.xRot = 0.0f
        entity.yHeadRotO = 0.0f
        entity.yHeadRot = 0.0f
        val pose = guiGraphics.pose()
        pose.pushPose()
        pose.translate(x.toFloat(), y.toFloat(), 50.0f)
        pose.scale(-size, size, size)
        pose.mulPose(Axis.ZP.rotationDegrees(180.0f))
        pose.mulPose(Axis.YP.rotationDegrees(135.0f))
        Lighting.setupForEntityInInventory()
        pose.mulPose(Axis.YP.rotationDegrees(-135.0f))
        pose.translate(0.0f, 0.0f, zOffset.toFloat())
        pose.mulPose(Axis.YP.rotationDegrees(currentModelYaw))
        pose.mulPose(Axis.XP.rotationDegrees(0.25f))
        val renderManager = Minecraft.getInstance().entityRenderDispatcher
        renderManager.setRenderShadow(false)
        RenderSystem.runAsFancy {
            renderManager.render(entity, 0.0, 0.0, 0.0, 0.0f, partialTicks, pose, guiGraphics.bufferSource(), LightTexture.FULL_BRIGHT)
        }
        guiGraphics.flush()
        renderManager.setRenderShadow(true)
        pose.popPose()
        Lighting.setupFor3DItems()
        entity.yBodyRot = renderYawOffset
        entity.yRot = rotationYaw
        entity.xRot = rotationPitch
        entity.yHeadRotO = prevRotationYawHead
        entity.yHeadRot = rotationYawHead
    }

    open fun startRendering() {
        if (!this.isRendering) {
            var fps = Minecraft.getInstance().fps.toFloat()
            if (fps == 0.0f) {
                fps = 0.1f
            }

            if (this.scrollVelocity == 0) {
                currentModelYaw = currentModelYaw + this.scrollDirection * 10 / fps
            } else {
                currentModelYaw = currentModelYaw + this.scrollVelocity / fps
                this.scrollVelocity = (this.scrollVelocity * (1.0f - 0.25f / fps)).toInt()
                if (Math.abs(this.scrollVelocity) <= 10) {
                    this.scrollDirection = if (this.scrollVelocity > 0) 1 else -1
                    this.scrollVelocity = 0
                }
            }
        }
    }

    /** CLIENT-ONLY key (open customisation, keyBindings[1]) and drag-scroll tick handler. */
    open class KeyAndScrollHandler {
        @SubscribeEvent
        open fun onKeyInput(event: InputEvent.Key) {
            if (ClientProxy.keyBindings[1].consumeClick()) {
                if (ServerWhitelistManager.isGlobalRenderingDisabled) {
                    ServerWhitelistManager.isGlobalRenderingDisabled = 0 != ServerWhitelistManager.getModelCount(true)
                    if (ServerWhitelistManager.isGlobalRenderingDisabled) {
                        return
                    }
                }

                val mc = Minecraft.getInstance()
                // K key: open the reskin browser (or the download prompt when no
                // packs are installed).
                ReskinBrowserScreen.show(mc, null)
            }
        }

        /** 1.12 ran this on both tick phases (no phase check); both are kept. */
        @SubscribeEvent
        open fun onClientTick(event: ClientTickEvent.Pre) {
            tickScroll()
        }

        @SubscribeEvent
        open fun onClientTickPost(event: ClientTickEvent.Post) {
            tickScroll()
        }

        private fun tickScroll() {
            targetScrollOffset = scrollOffset
            scrollOffset = 0

            for (id in selectedPartIds) {
                scrollOffset = scrollOffset + id
            }

            selectedPartIds.clear()
        }
    }

    companion object {
        @JvmField
        val GUI_TEXTURE: ResourceLocation = Main.rl("textures/gui/clothing_icons.png")

        /** Static cross placeholder for the (disabled) legacy part rows — never a real model. */
        @JvmField
        var DUMMY_SCENE: SexSceneEntity? = null
        const val SCROLLBAR_SIZE: Int = 20
        const val SCROLL_SPEED: Float = 0.25f

        @JvmField
        var currentModelYaw: Float = 0.0f

        @JvmField
        var selectedPartIds: MutableList<Int> = ArrayList()

        @JvmField
        var scrollOffset: Int = 0

        @JvmField
        var targetScrollOffset: Int = 0

        @JvmField
        var customPartEntries: MutableList<MutableMap.MutableEntry<BoneType, MutableMap.MutableEntry<MutableList<String>, Int>>> = ArrayList()

        @JvmStatic
        fun getCustomBoneNames(): HashSet<String> {
            val names = HashSet<String>()

            for (entry in customPartEntries) {
                if (entry.value.key.size != 1) {
                    val data = entry.value
                    val parts = data.key
                    val index = data.value
                    names.add(parts[index])
                }
            }

            return names
        }

        @JvmStatic
        fun getCustomPartData(girl: BaseGirlEntity): MutableMap.MutableEntry<BoneType, MutableMap.MutableEntry<MutableList<String>, Int>> {
            val parts = ArrayList<String>()
            parts.add("cross")
            parts.addAll(ServerWhitelistManager.getModelParts(girl)[BoneType.CUSTOM_BONE] ?: emptyList())
            return SimpleEntry(BoneType.CUSTOM_BONE, SimpleEntry<MutableList<String>, Int>(parts, 0))
        }

        @JvmStatic
        fun openClothingScreen(girl: BaseGirlEntity) {
            val mc = Minecraft.getInstance()
            if (mc.screen !is ClothingScreen) {
                val canUseCustomModels = ServerWhitelistManager.getCustomModelsKey() == null || ServerWhitelistManager.isGlobalRenderingDisabled()
                if (!canUseCustomModels) {
                    mc.player?.displayClientMessage(
                        Component.literal("You have to whitelist the server to use its custom models. " + ChatFormatting.YELLOW + "/whitelistserver"), true
                    )
                } else {
                    mc.execute { mc.setScreen(ClothingScreen(girl)) }
                }
            }
        }
    }
}
