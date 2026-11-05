package com.trolmastercard.sexmod.client.gui

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BoneType
import com.trolmastercard.sexmod.entity.SexSceneEntity
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.ServerWhitelistManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth

/**
 * Scrollable list of model/part entries shown in the [ClothingScreen]
 * customization GUI. One row per [BoneType] (plus a special "add custom
 * part" row), each row rendering a preview of the currently selected model via
 * a temporary [SexSceneEntity] and offering the girl-specific part
 * toggles and the "cross/next" buttons.
 *
 * **Data flow.** Entries are rebuilt every frame from
 * [ClothingScreen.customPartEntries] (static custom-part registry map) and
 * [ServerWhitelistManager.getModelParts]. Preview [SexSceneEntity]s
 * are created and discarded within a single draw call — they must never
 * outlive the frame (they are never added to the level).
 *
 * CLIENT-side only. Scrolling is mouse-wheel driven and ignores the vanilla
 * scroll bar (bar is drawn but non-functional by design).
 *
 * 1.21 port: the 1.12 `GuiListExtended` machinery this list used (slot layout,
 * `amountScrolled`, clamping, the hand-drawn scroll bar) is reproduced in a
 * plain class drawing through the parent screen's [ClothingScreen.currentGraphics];
 * a vanilla `ObjectSelectionList` would change the hit-testing the rows rely on.
 * The decompiled parameter names of the row helpers were shifted by one; they
 * are named after their real meaning here (logic unchanged).
 */
open class CustomModelList(@JvmField val mc: Minecraft, parentScreen: ClothingScreen) {
    private val entries: MutableList<ModelListEntry> = ArrayList()

    @JvmField
    var parentScreen: ClothingScreen = parentScreen

    @JvmField
    var needsRefresh: Boolean = false

    @JvmField
    var scrollOffset: Float = 0.0f

    // ---- 1.12 GuiSlot state ----
    @JvmField
    var width: Int = parentScreen.width / 2

    @JvmField
    var height: Int = parentScreen.height

    @JvmField
    var top: Int = 0

    @JvmField
    var bottom: Int = parentScreen.height

    @JvmField
    var left: Int = 0

    @JvmField
    var right: Int = parentScreen.width / 2

    @JvmField
    val slotHeight: Int = 30

    @JvmField
    var amountScrolled: Float = 0.0f

    @JvmField
    var mouseX: Int = 0

    @JvmField
    var mouseY: Int = 0

    @JvmField
    var visible: Boolean = true

    init {
        SCROLL_SPEED = parentScreen.width / 2
    }

    open fun getSize(): Int = this.entries.size

    open fun getScrollBarX(): Int = 0

    /** 1.12 `GuiSlot.getListWidth()`. */
    open fun getListWidth(): Int = 220

    open fun getContentHeight(): Int = this.getSize() * this.slotHeight

    open fun getMaxScroll(): Int = Math.max(0, this.getContentHeight() - (this.bottom - this.top - 4))

    open fun getAmountScrolled(): Int = this.amountScrolled.toInt()

    /** 1.12 `bindAmountScrolled`: clamps the scroll amount. */
    open fun bindAmountScrolled() {
        this.amountScrolled = Mth.clamp(this.amountScrolled, 0.0f, this.getMaxScroll().toFloat())
    }

    open fun scrollBy(amount: Int) {
        this.amountScrolled += amount.toFloat()
        this.bindAmountScrolled()
    }

    open fun isMouseYWithinSlotBounds(mouseY: Int): Boolean =
        mouseY >= this.top && mouseY <= this.bottom && this.mouseX >= this.left && this.mouseX <= this.right

    /**
     * Scrolls by one half-slot per wheel notch, but only while the cursor is
     * within the list bounds. 1.21: called from [ClothingScreen.mouseScrolled].
     */
    open fun handleMouseInput(wheelDelta: Double) {
        if (this.isMouseYWithinSlotBounds(this.mouseY)) {
            if (wheelDelta != 0.0) {
                val direction: Int = if (wheelDelta > 0) -1 else 1
                this.amountScrolled = this.amountScrolled + direction * this.slotHeight / 2
            }
        }
    }

    /**
     * Vertically centers the list on screen when the content fits, otherwise
     * pins it to the top so scrolling covers the full content height.
     */
    open fun updateScrollbar() {
        val contentHeight = this.entries.size * this.slotHeight
        if (contentHeight > this.height) {
            this.top = 0
        } else {
            val remaining = this.height - contentHeight
            this.top = remaining / 2
        }
    }

    /**
     * Rebuilds and sorts the entries from [ClothingScreen.customPartEntries] (BoneType ->
     * (model names, selected index)), prepends the `cross` entry to the
     * custom-bone models, appends the "add custom part" row (only when at least
     * one custom bone exists), then refreshes scroll state.
     */
    open fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
        this.entries.clear()
        var customBoneCount = 0

        for (entry in ClothingScreen.customPartEntries) {
            val boneType = entry.key
            val data = entry.value
            // Legacy rows: girl-specific parts (scale sliders) and custom-bone parts
            // are part of the old wardrobe. The reskin browser adds a NEW overlay on
            // top; keep both working (renderer crash-guards missing legacy models).
            this.entries.add(ModelListEntry(boneType, data.key, data.value))
            if (BoneType.CUSTOM_BONE == entry.key) {
                customBoneCount++
            }
        }

        this.entries.sortBy { boneTypes.indexOf(it.boneType) }
        val customParts = ServerWhitelistManager.getModelParts(this.parentScreen.previewGirl)[BoneType.CUSTOM_BONE]
        customParts?.add(0, "cross")
        this.entries.add(ModelListEntry(customBoneCount > 1))
        this.updateScrollbar()
        this.onMouseMove(mouseX, mouseY, partialTicks)
        if (this.needsRefresh) {
            this.scrollBy(999999)
            this.needsRefresh = false
        }
    }

    /**
     * Full draw pass: the rows and a hand-rolled scroll bar
     * (track, thumb and highlight). Mostly 1.12 `GuiSlot` machinery, kept
     * because the custom entries need precise mouse-hit testing.
     */
    open fun onMouseMove(mouseX: Int, mouseY: Int, partialTicks: Float) {
        if (this.visible) {
            val guiGraphics = this.parentScreen.currentGraphics ?: return
            this.mouseX = mouseX
            this.mouseY = mouseY
            val scrollBarX = this.getScrollBarX()
            val thumbX = scrollBarX + 6
            this.bindAmountScrolled()
            val left = this.left + this.width / 2 - this.getListWidth() / 2 + 2
            val top = this.top + 4 - this.amountScrolled.toInt()

            // 1.12 drawSelectionBox
            for (i in 0 until this.getSize()) {
                val rowY = top + i * this.slotHeight
                val rowH = this.slotHeight - 4
                if (rowY > this.bottom || rowY + rowH < this.top) continue
                this.entries[i].drawEntry(i, left, rowY, this.getListWidth(), rowH, mouseX, mouseY, false, partialTicks)
            }

            val maxScroll = this.getMaxScroll()
            if (maxScroll > 0) {
                var thumbHeight = (this.bottom - this.top) * (this.bottom - this.top) / this.getContentHeight()
                thumbHeight = Mth.clamp(thumbHeight, 32, this.bottom - this.top - 8)
                var thumbY = this.amountScrolled.toInt() * (this.bottom - this.top - thumbHeight) / maxScroll + this.top
                if (thumbY < this.top) {
                    thumbY = this.top
                }

                guiGraphics.fill(scrollBarX, this.top, thumbX, this.bottom, 0xFF000000.toInt())
                guiGraphics.fill(scrollBarX, thumbY, thumbX, thumbY + thumbHeight, 0xFF808080.toInt())
                guiGraphics.fill(scrollBarX, thumbY, thumbX - 1, thumbY + thumbHeight - 1, 0xFFC0C0C0.toInt())
            }
        }
    }

    open fun mouseClicked(mouseX: Int, mouseY: Int, mouseButton: Int): Boolean {
        this.getSlotIndex(mouseX, mouseY, mouseButton)
        return false
    }

    /**
     * Computes which entry the mouse is over and routes the click to its
     * row-background handler ([ModelListEntry.drawBackground]), which is
     * what implements the per-row buttons.
     */
    open fun getSlotIndex(mouseX: Int, mouseY: Int, mouseButton: Int) {
        val listLeft = this.left + this.width / 2 - this.getListWidth() / 2 + 2
        val contentLeft = listLeft
        Main.LOGGER.info(String.format("[DEBUG hit] mouseX=%d mouseY=%d contentLeft=%d width=%d listWidth=%d", mouseX, mouseY, contentLeft, this.width, this.getListWidth()))
        val scrolled = this.getAmountScrolled()
        val relativeY = (scrolled + mouseY - 5 - this.top).toFloat()
        val rowIndex = Math.round(Math.floor((relativeY / this.slotHeight).toDouble()).toFloat())
        val rowY = Math.round((relativeY / this.slotHeight - Math.floor((relativeY / this.slotHeight).toDouble())) * this.slotHeight).toInt()
        Main.LOGGER.info(String.format("[DEBUG hit] row=%d rowY=%d relY=%.1f scrolled=%d top=%d", rowIndex, rowY, relativeY, scrolled, this.top))
        if (rowIndex >= 0) {
            if (rowIndex < this.entries.size) {
                val relX = mouseX - contentLeft
                Main.LOGGER.info(String.format("[DEBUG hit] -> entry %s relX=%d", this.entries[rowIndex].boneType, relX))
                this.entries[rowIndex].drawBackground(relX, rowY, mouseButton, rowIndex)
            }
        }
    }

    /**
     * One list row. Three kinds, selected by constructor: a model row for a
     * [BoneType] (preview + model-name labels), the custom-part header row
     * (add/remove buttons), and the girl-specific part rows (toggle + scale
     * slider). Draws a temporary [SexSceneEntity] as the part preview and
     * forwards clicks to the parent [ClothingScreen] for state changes.
     */
    inner class ModelListEntry {
        @JvmField
        var boneType: BoneType? = null

        @JvmField
        var modelNames: List<String> = ArrayList()

        @JvmField
        var selectedIndex: Int = 0

        @JvmField
        var fontRenderer: Font = this@CustomModelList.mc.font

        @JvmField
        var isVisible: Boolean = false

        @JvmField
        var isSelected: Boolean = false

        constructor(boneType: BoneType, modelNames: List<String>, selectedIndex: Int) {
            this.boneType = boneType
            this.modelNames = modelNames
            this.selectedIndex = selectedIndex
        }

        constructor(isSelected: Boolean) {
            this.isSelected = isSelected
            this.isVisible = true
        }

        private val screen: ClothingScreen
            get() = this@CustomModelList.parentScreen

        fun isInBounds(x: Int, y: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean {
            return if (x < left) {
                false
            } else if (x > right) {
                false
            } else {
                if (y < top) false else y <= bottom
            }
        }

        fun getScrollY(yIn: Int, mouseX: Int, mouseY: Int) {
            // Add/remove-custom-part header row: the "+" button re-registers the
            // current custom part, the "x" (only while selected) removes it.
            var bx = 30
            val y = yIn + 5
            screen.drawTexturedModalRect(bx, y, 40, if (this.isInBounds(mouseX, mouseY, bx, y, bx + 20, y + 20)) 40 else 20, 20, 20)
            bx += 40
            screen.drawTexturedModalRect(bx, y, if (this.isSelected) 60 else 80, if (this.isInBounds(mouseX, mouseY, bx, y, bx + 20, y + 20)) 40 else 20, 20, 20)
        }

        /**
         * Renders a bone-category row: the part background, category icon, the
         * < / > cycle buttons, the 3D part preview (a temporary
         * [SexSceneEntity]) and, for non-cross entries, the model name +
         * author labels with hover tooltips. Restored from the original jar —
         * the reskin browser sits on top of this, both stay functional.
         */
        fun drawEntry(yIn: Int, mouseX: Int, mouseY: Int) {
            val boneType = this.boneType ?: return
            screen.drawTexturedModalRect(5, yIn, 0, 60, if (this.selectedIndex == 0) 119 else 256, 30)
            var x = 15
            val y = yIn + 5
            screen.drawPartIcon(x, y, boneType.iconXPos)
            x += 25
            x = this.getEntryWidth(x, y, mouseX, mouseY)
            val previewGirl = screen.getPreviewGirl()
            val level = this@CustomModelList.mc.level ?: return
            val scene: SexSceneEntity = if (this.selectedIndex == 0) {
                SexSceneEntity.createSceneEntity(level, previewGirl.getGirlId(), boneType)
            } else {
                SexSceneEntity(previewGirl.level(), previewGirl.getGirlId(), this.modelNames[this.selectedIndex])
            }
            val modelCode = scene.getModelCode()
            val modelData = if (modelCode == null) null else ServerWhitelistManager.getModelDataForGirl(modelCode)
            val scale: Float
            val yOff: Int
            if (modelData != null) {
                scale = if (!scene.isItemModel) modelData.getScale() else 1.0f
                yOff = (-modelData.getXOffset()).toInt()
            } else {
                scale = 1.0f
                yOff = 0
            }
            screen.drawPart(x, y + 10 + (if (scene.isItemModel) 0 else 6) + yOff, 30.0f * scale, scene)
            if (this.selectedIndex != 0) {
                screen.drawPreviewModel(scene)
            }
            // 1.12 removeEntityDangerously: the preview scene was never added to the level
            scene.discard()
            x = (x + 30.0f).toInt()
            if (this.selectedIndex != 0) {
                val nameX = x
                val fullName = this.modelNames[this.selectedIndex]
                val shortName = if (fullName.length > 10) fullName.substring(0, 7) + "..." else fullName
                this.drawEntryLabel(shortName, x, y + 10)
                x += this.fontRenderer.width(PLACEHOLDER_NAME)
                val authorX = x
                val author = ServerWhitelistManager.getModelCode(fullName)
                val shortAuthor = if (author.length > 10) author.substring(0, 7) + "..." else author
                this.drawEntryLabel(shortAuthor, x, y + 10)
                x += this.fontRenderer.width(PLACEHOLDER_NAME)
                val authorEnd = x
                if (this.isInBounds(mouseX, mouseY, nameX, y + 10, nameX + this.fontRenderer.width(PLACEHOLDER_NAME), y + 10 + this.fontRenderer.lineHeight)) {
                    screen.drawHoverText(fullName, mouseX, mouseY)
                }
                if (this.isInBounds(mouseX, mouseY, authorX, y + 10, authorEnd, y + 10 + this.fontRenderer.lineHeight)) {
                    screen.drawHoverText(author, mouseX, mouseY)
                }
            }
        }

        /**
         * Draws the girl-specific part toggle button (left: off, right: on,
         * highlighted when hovered).
         */
        fun getEntryWidth(xIn: Int, y: Int, mouseX: Int, mouseY: Int): Int {
            var x = xIn
            screen.drawPartBackground(x, y, 0, 20 * (if (this.isInBounds(mouseX, mouseY, x, y, x + 20, y + 20)) 2 else 1))
            x += 20
            screen.drawPartBackground(x, y, 20, 20 * (if (this.isInBounds(mouseX, mouseY, x, y, x + 20, y + 20)) 2 else 1))
            return x + 40
        }

        /**
         * Draws the scale slider track and thumb, and immediately persists the
         * slider value onto the preview girl via
         * `setCustomPartValue` — the preview updates live as the slider is
         * dragged.
         */
        fun onEntryClick(xIn: Int, y: Int, mouseX: Int, mouseY: Int, partIndex: Int) {
            var x = xIn
            screen.drawTexturedModalRect(x, y, 140, 20, 79, 20)
            x += 4
            val trackStart = x
            val trackEnd = x + 71 - 4
            val scale = this.getPartScale(y, trackStart, trackEnd, mouseX, mouseY, partIndex)
            val thumbX = RotationHelper.lerp(trackStart.toFloat(), trackEnd.toFloat(), scale).toInt()
            screen.drawTexturedModalRect(thumbX, y, if (this.isInBounds(mouseX, mouseY, thumbX, y, thumbX + 4, y + 20)) 223 else 219, 20, 4, 20)
            // Store the OPTION INDEX (0..count-1), not a 0..100 percentage. The
            // confirm packet sends these as part ids and the server validates
            // currentId > partId, so a bare percentage (e.g. 75) is always rejected.
            val max = this.optionCount(partIndex)
            val opt = Math.max(0, Math.min(Math.round(scale * (max - 1)), max - 1))
            screen.previewGirl.setCustomPartValue(partIndex, opt)
        }

        fun optionCount(partIndex: Int): Int {
            return try {
                val data = screen.previewGirl.buildCustomPartsData(screen.girlId)[partIndex]
                val size = data.value.key.size
                Math.max(1, size)
            } catch (t: Throwable) {
                1
            }
        }

        /**
         * Maps the mouse X to a slider fraction in 0..1. When not editing, or when
         * the mouse is outside the slider track, returns the current stored value
         * instead, so the slider only changes while actively dragged.
         */
        fun getPartScale(rowY: Int, trackStart: Int, trackEnd: Int, mouseX: Int, mouseY: Int, partIndex: Int): Float {
            var mx = mouseX
            var end = trackEnd
            if (!screen.isEditing) {
                return this.getPartValue(partIndex)
            }

            if (mx > 0.33333334f * screen.width) {
                return this.getPartValue(partIndex)
            }

            if (mouseY < rowY || mouseY > rowY + 20) {
                return this.getPartValue(partIndex)
            }

            if (mx < trackStart) {
                return 0.0f
            }

            if (mx > end) {
                return 1.0f
            }

            end -= trackStart
            mx -= trackStart
            return mx.toFloat() / end
        }

        fun getPartValue(partIndex: Int): Float {
            val max = this.optionCount(partIndex)
            val data = screen.previewGirl.buildCustomPartsData(screen.girlId)[partIndex]
            val v = data.value.value
            if (max <= 1) return 1.0f
            return Math.max(0.0f, Math.min(1.0f, v / (max - 1).toFloat()))
        }

        /**
         * Draws a girl-specific row: the part toggle + slider when the part is
         * enabled, or just the (dimmed) toggle when disabled.
         */
        fun isEntrySelected(yIn: Int, mouseX: Int, mouseY: Int, partIndex: Int) {
            if (screen.previewGirl.isPartEnabled(partIndex)) {
                screen.drawTexturedModalRect(5, yIn, 0, 60, 119, 30)
                var x = 15
                val y = yIn + 5
                screen.drawPartAt(x, y, screen.previewGirl.getModelPartByIndex(partIndex))
                x += 25
                this.onEntryClick(x, y, mouseX, mouseY, partIndex)
            } else {
                screen.drawTexturedModalRect(5, yIn, 0, 90, 95, 30)
                var x2 = 15
                val y = yIn + 5
                screen.drawPartAt(x2, y, screen.previewGirl.getModelPartByIndex(partIndex))
                x2 += 25
                this.getEntryWidth(x2, y, mouseX, mouseY)
            }
        }

        /** 1.12 `IGuiListEntry.drawEntry(slotIndex, x, y, listWidth, slotHeight, mouseX, mouseY, isSelected, partialTicks)`. */
        fun drawEntry(slotIndex: Int, x: Int, y: Int, listWidth: Int, slotHeight: Int, mouseX: Int, mouseY: Int, isHovered: Boolean, partialTicks: Float) {
            if (this.isVisible) {
                this.getScrollY(y, mouseX, mouseY)
            } else if (this.boneType == BoneType.GIRL_SPECIFIC) {
                this.isEntrySelected(y, mouseX, mouseY, slotIndex)
            } else {
                this.drawEntry(y, mouseX, mouseY)
            }
        }

        fun drawEntryLabel(text: String, x: Int, y: Int) {
            screen.currentGraphics?.drawString(this.fontRenderer, text, x, y, TEXT_COLOR, false)
        }

        fun handleScrollClick(x: Int, y: Int) {
            var buttonX = 30
            if (x > buttonX && x < 50) {
                this@CustomModelList.needsRefresh = true
                this@CustomModelList.mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
                val parts = ArrayList<String>()
                parts.add("cross")
                parts.addAll(ServerWhitelistManager.getModelParts(screen.previewGirl)[BoneType.CUSTOM_BONE] ?: emptyList())
                ClothingScreen.customPartEntries.add(ClothingScreen.getCustomPartData(screen.previewGirl))
            }

            if (this.isSelected) {
                buttonX += 40
                if (x > buttonX && x < buttonX + 20) {
                    this@CustomModelList.mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
                    ClothingScreen.customPartEntries.removeAt(ClothingScreen.customPartEntries.size - 1)
                }
            }
        }

        fun handleSlotClick(x: Int, partIndex: Int) {
            val boneType = this.boneType ?: return
            // < / > cycle the selected part for this bone row (and enable/disable
            // the girl-specific parts). Restored from the original jar — this is
            // what actually switches the legacy part applied to the preview girl.
            if (x > 40 && x < 60) {
                screen.onBoneTypeToggle(boneType, false, partIndex)
            }
            if (x > 60 && x < 80) {
                screen.onBoneTypeToggle(boneType, true, partIndex)
            }
        }

        fun isGirlSpecific(x: Int, partIndex: Int) {
            if (!screen.previewGirl.isPartEnabled(partIndex)) {
                this.handleSlotClick(x, partIndex)
            }
        }

        /**
         * Row-background click routing (the "buttons" of a row). For the custom-part
         * header row: the add button re-registers the current custom part into
         * [ClothingScreen.customPartEntries] and marks the list for refresh; the remove button
         * (only when the header is selected) drops the last entry. For all other
         * rows: girl-specific rows toggle the part via
         * `ClothingScreen.onBoneTypeToggle`.
         */
        fun drawBackground(mouseX: Int, mouseY: Int, mouseButton: Int, rowIndex: Int) {
            if (mouseButton == 0) {
                if (mouseY >= 5) {
                    if (mouseY <= 25) {
                        if (this.isVisible) {
                            this.handleScrollClick(mouseX, mouseY)
                        } else if (this.boneType == BoneType.GIRL_SPECIFIC) {
                            this.isGirlSpecific(mouseX, rowIndex)
                        } else {
                            this.handleSlotClick(mouseX, rowIndex)
                        }
                    }
                }
            }
        }

        fun updatePosition(x: Int, y: Int, mouseX: Int, partialTicks: Float) {
        }

        fun mousePressed(slotIndex: Int, x: Int, y: Int, mouseX: Int, mouseY: Int, partialTicks: Int): Boolean = false

        fun mouseReleased(slotIndex: Int, x: Int, y: Int, mouseX: Int, mouseY: Int, partialTicks: Int) {
        }
    }

    companion object {
        const val TEXT_COLOR: Int = 3809871

        @JvmField
        val boneTypes: List<BoneType> = BoneType.entries.toList()
        const val PLACEHOLDER_NAME: String = "MMMMMMMMMM"

        @JvmField
        var SCROLL_SPEED: Int = 200
    }
}
