package com.trolmastercard.sexmod.reskin

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.gui.ClothingScreen
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.NpcType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.level.Level
import java.awt.image.BufferedImage
import java.io.File
import java.util.Collections
import java.util.Locale

/**
 * **Role.** Two-column pack browser modelled EXACTLY on vanilla's
 * resource-pack screen:
 * - Left = AVAILABLE packs (filtered by girl chips + search).
 * - Right = APPLIED stack — index 0 at top = highest priority (overrides), lower = fallback.
 * - 32x32 thumbnail in BOTH columns.
 * - Double-click a LEFT row -> applies the pack (moves it to the TOP of the applied stack).
 * - Double-click a RIGHT row -> removes it from the stack (back to available).
 * - Move up / Move down buttons beside the right column re-order the selected stack row.
 * - Applied packs are excluded from the left list exactly like vanilla excludes selected
 *   resourcepacks from the available list.
 *
 * CLIENT-ONLY. 1.21 port: `GuiTextField` -> [EditBox] widget (input handled by
 * the screen's widget dispatch; typing resets the scroll via its responder),
 * `handleMouseInput` wheel -> [mouseScrolled], drawing through [GuiGraphics].
 */
open class ReskinBrowserScreen : Screen {
    private val manager: ReskinManager = ReskinManager.INSTANCE
    private var entries: List<ReskinEntry>? = null
    private var scrollAvailable = 0
    private var scrollStack = 0
    private var selectedAvailable = -1
    private var selectedStack = -1
    private var status: String? = ""
    private var filterGirl: String?
    private var searchField: EditBox? = null
    private var chipRows = 1

    @Volatile
    private var scanDone = false
    private val thumbTextures: MutableMap<String, ResourceLocation> = HashMap()
    private val pendingThumbs: MutableMap<String, BufferedImage> = HashMap()
    private val previewGirls: MutableList<BaseGirlEntity> = ArrayList()

    @Volatile
    private var thumbsDirty = false

    // double-click detection for apply (left) / remove (right)
    private var dblLastClickMs: Long = 0
    private var dblLastRow = -1

    constructor() : this(null)

    constructor(startGirl: String?) : this(startGirl, null)

    /** Opens the browser with a pre-set status line (e.g. after a download). */
    constructor(startGirl: String?, initialStatus: String?) : super(Component.literal("Reskins")) {
        this.filterGirl = startGirl
        if (initialStatus != null && initialStatus.isNotEmpty()) this.status = initialStatus
        this.minecraft = Minecraft.getInstance()
        // Original behavior: decode thumbnails eagerly (background thread) into
        // pendingThumbs; GL upload happens on the render thread in drawThumbsIfReady.
        // This is NOT cached across opens (gl textures must upload on render thread),
        // so each GUI open re-decodes but shows real thumbnails, not grey.
        manager.submit {
            try {
                manager.scan()
                for (e in manager.scan()) {
                    if (e.previewName != null) {
                        val img = manager.preview(e)
                        if (img != null) {
                            synchronized(pendingThumbs) {
                                pendingThumbs[e.source.absolutePath] = img
                            }
                            thumbsDirty = true
                        }
                    }
                }
            } catch (t: Throwable) {
                Main.LOGGER.error("could not build the reskin thumbnails", t)
            } finally {
                scanDone = true
            }
        }
    }

    override fun init() {
        super.init()
        var field = this.searchField
        if (field == null) {
            field = EditBox(this.font, this.width - 168, 6, 160, 16, Component.empty())
            field.setMaxLength(40)
            field.setCanLoseFocus(true)
            field.setResponder { scrollAvailable = 0 }
            this.searchField = field
        } else {
            field.x = this.width - 168
            field.y = 6
        }
        this.addRenderableWidget(field)
    }

    override fun isPauseScreen(): Boolean = false

    /* ------------------------------------------------------------------ */
    /* Layout & data                                                     */
    /* ------------------------------------------------------------------ */

    private fun listTop(): Int = 26 + chipRows * (CHIP_H + 2)

    private fun listBottom(): Int = this.height - LIST_BOTTOM_MARGIN

    /** Left (available) column X — centered around width/2 like vanilla's pack GUI. */
    private fun colLeftX(): Int = Math.max(4, this.width / 2 - 4 - COL_W)

    /** Right (applied) column X. */
    private fun colRightX(): Int = this.width / 2 + 4

    private fun visibleRows(): Int = Math.max(1, (listBottom() - listTop()) / ROW_H)

    /** Available = all packs minus any currently applied (vanilla excludes selected). */
    private fun displayList(): List<ReskinEntry> {
        val all = manager.scan()
        val q = searchField?.value?.trim()?.lowercase(Locale.ROOT) ?: ""
        val applied = manager.appliedCopy()
        val out = ArrayList<ReskinEntry>()
        for (e in all) {
            if (applied.contains(e)) continue // hide applied from available (vanilla behavior)
            if (filterGirl != null && !e.girls.contains(filterGirl)) continue
            if (q.isNotEmpty()) {
                if (!e.displayName.lowercase(Locale.ROOT).contains(q) &&
                    !e.girlSummary().lowercase(Locale.ROOT).contains(q)
                ) continue
            }
            out.add(e)
        }
        return out
    }

    /** Applied stack ordered top-first (index 0 = highest priority / most recent). */
    private fun appliedStack(): List<ReskinEntry> {
        val out = ArrayList(manager.appliedCopy())
        Collections.reverse(out)
        return out
    }

    private fun setFilter(girl: String?) {
        filterGirl = girl
        scrollAvailable = 0
        status = if (girl == null) "Showing ALL character packs" else "Showing " + girl.uppercase() + " packs"
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                             */
    /* ------------------------------------------------------------------ */

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        val dw = Math.signum(scrollY).toInt()
        val list = entries
        if (dw == 0 || list == null) return true
        if (mouseX < colRightX()) {
            scrollAvailable -= dw
            scrollAvailable = Math.max(0, Math.min(scrollAvailable, Math.max(0, list.size - visibleRows())))
        } else {
            scrollStack -= dw
            scrollStack = Math.max(0, Math.min(scrollStack, Math.max(0, appliedStack().size - visibleRows())))
        }
        return true
    }

    override fun mouseClicked(mouseXd: Double, mouseYd: Double, mouseButton: Int): Boolean {
        val handled = super.mouseClicked(mouseXd, mouseYd, mouseButton)
        if (mouseButton != 0) return handled
        val mouseX = mouseXd.toInt()
        val mouseY = mouseYd.toInt()

        // chips
        var cx = 8
        var cy = 26
        chipRows = 1
        val chips = ArrayList<String?>()
        chips.add(null)
        for (g in ReskinScanner.GIRLS) chips.add(g)
        for (g in chips) {
            if (cx + CHIP_W > this.width - 4) {
                cx = 8
                cy += CHIP_H + 2
                chipRows++
            }
            if (mouseX >= cx && mouseX <= cx + CHIP_W && mouseY >= cy && mouseY <= cy + CHIP_H) {
                setFilter(g)
                return true
            }
            cx += CHIP_W + CHIP_GAP
        }

        val list = entries ?: return handled
        val top = listTop()
        val rows = visibleRows()

        // LEFT (available): single click = select; double-click = apply (moves to right)
        for (i in 0 until rows) {
            val idx = scrollAvailable + i
            if (idx >= list.size) break
            val y = top + i * ROW_H
            if (mouseX >= colLeftX() && mouseX <= colLeftX() + COL_W && mouseY >= y && mouseY < y + ROW_H) {
                selectedAvailable = idx
                val now = System.currentTimeMillis()
                val dbl = now - dblLastClickMs < 350 && dblLastRow == idx
                dblLastClickMs = now
                dblLastRow = idx
                if (dbl) {
                    applyRow(list[idx]) // double-click = apply
                }
                return true
            }
        }

        // RIGHT (applied stack): single click = select; double-click = remove
        val st = appliedStack()
        for (i in 0 until rows) {
            val idx = scrollStack + i
            if (idx >= st.size) break
            val y = top + i * ROW_H
            if (mouseX >= colRightX() && mouseX <= colRightX() + COL_W && mouseY >= y && mouseY < y + ROW_H) {
                selectedStack = idx
                val now = System.currentTimeMillis()
                val dbl = (if (now < dblLastClickMs) false else now - dblLastClickMs < 350) && dblLastRow == idx + 10000 // separate right-list double-click tracking
                // NOTE: shared tracker is fine since a double-click on same row/col is impossible
                dblLastClickMs = now
                dblLastRow = idx + 10000 // offset to distinguish from left list
                if (dbl) {
                    revertRow(st[idx]) // double-click = remove from stack
                    selectedStack = -1
                }
                return true
            }
        }

        // external Move up / Move down buttons (beside right column, top area)
        val bw2 = 74
        val bh2 = 18
        val ux = colRightX() + COL_W + 8
        val uy = listTop()
        if (mouseX >= ux && mouseX <= ux + bw2 && mouseY >= uy && mouseY <= uy + bh2) {
            moveSelected(-1) // Move up
            return true
        }
        if (mouseX >= ux && mouseX <= ux + bw2 && mouseY >= uy + bh2 + 4 && mouseY <= uy + bh2 + 4 + bh2) {
            moveSelected(+1) // Move down
            return true
        }

        // bottom buttons
        val bw = 64
        val bh = 20
        val gap = 5
        val labels = arrayOf("Apply", "Remove", "Revert All", "Legacy", "Body", "Done")
        val total = labels.size * bw + (labels.size - 1) * gap
        val bx = (this.width - total) / 2
        val by = this.height - 32
        for (li in labels.indices) {
            val x0 = bx + li * (bw + gap)
            if (mouseX >= x0 && mouseX <= x0 + bw && mouseY >= by && mouseY <= by + bh) {
                onButton(labels[li])
                return true
            }
        }
        return handled
    }

    /** Apply the pack (moves to TOP of stack, loads immediately). */
    private fun applyRow(e: ReskinEntry) {
        status = manager.apply(e)
        scrollStack = 0
    }

    /** Applies the selected stack row (UP/DOWN). */
    private fun moveSelected(delta: Int) {
        val st = appliedStack()
        if (selectedStack < 0 || selectedStack >= st.size) {
            status = "Select a pack on the RIGHT first."
            return
        }
        moveRow(st[selectedStack], delta)
    }

    private fun revertRow(e: ReskinEntry) {
        status = manager.revert(e)
    }

    /** Move an applied pack up/down within the stack (re-order = change priority). */
    private fun moveRow(e: ReskinEntry, delta: Int) {
        status = manager.move(e, delta)
    }

    private fun onButton(label: String) {
        try {
            Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
        } catch (ignored: Throwable) {
            // ignored: a missing click sound must not break the screen
        }
        when (label) {
            "Apply" -> {
                // apply the selected (highlighted) available pack; if none selected, the first visible
                val list = entries
                if (list != null && list.isNotEmpty()) {
                    val idx = if (selectedAvailable >= 0) selectedAvailable else Math.min(scrollAvailable, list.size - 1)
                    if (idx < list.size) applyRow(list[idx]) else status = "No pack selected."
                } else {
                    status = "No packs available."
                }
            }
            "Remove" -> {
                // remove the selected applied pack; with no selection, the top of the stack
                val st = appliedStack()
                if (st.isNotEmpty()) {
                    val idx = if (selectedStack in st.indices) selectedStack else 0
                    revertRow(st[idx])
                    selectedStack = -1
                } else {
                    status = "Nothing applied."
                }
            }
            "Revert All" -> status = manager.revertAll()
            "Legacy" -> openLegacy()
            "Body" -> openBodySliders()
            "Done" -> Minecraft.getInstance().setScreen(null)
            else -> {}
        }
    }

    /** Finds a real girl of [npcType] in the world, else creates a locally registered preview instance. */
    private fun findOrCreateGirl(npcType: NpcType): BaseGirlEntity {
        var girl: BaseGirlEntity? = null
        try {
            for (g in BaseGirlEntity.getGirlEntityList()) {
                if (NpcType.getNpcType(g) == npcType) {
                    girl = g
                    break
                }
            }
        } catch (t: Throwable) {
            // ignored: preview entity lookup is best effort; the placeholder is used below
        }
        if (girl == null) {
            girl = npcType.npcClass.getConstructor(Level::class.java).newInstance(Minecraft.getInstance().level)
            girl.setLocallyRegistered(true)
            this.previewGirls.add(girl)
        }
        return girl!!
    }

    private fun openLegacy() {
        try {
            val npcType = if (filterGirl == null) null else NpcType.getNpcTypeByName(filterGirl!!.uppercase(Locale.ROOT))
            if (npcType == null) {
                status = "Select a girl chip first (e.g. JENNY) so the old wardrobe has a subject."
                return
            }
            // Prefer a REAL girl of this type from the world (the old wardrobe was
            // designed for live girls); fall back to a fresh preview instance.
            val girl = findOrCreateGirl(npcType)
            // Use the original proven entry point (schedules + guards whitelist).
            ClothingScreen.openClothingScreen(girl)
            status = "Opened legacy wardrobe for " + npcType.name + "."
        } catch (t: Throwable) {
            status = "Could not open legacy wardrobe: " + t.javaClass.simpleName + ": " + t.message
        }
    }

    private fun openBodySliders() {
        try {
            val npcType = if (filterGirl == null) null else NpcType.getNpcTypeByName(filterGirl!!.uppercase(Locale.ROOT))
            if (npcType == null) {
                status = "Select a girl chip first to see her body sliders."
                return
            }
            // Prefer a REAL girl of this type from the world so the sliders can
            // persist to her; fall back to a fresh preview instance (display-only).
            val girl = findOrCreateGirl(npcType)
            Minecraft.getInstance().setScreen(BodySlidersScreen(girl))
        } catch (t: Throwable) {
            status = "Body sliders unavailable: " + t.javaClass.simpleName
        }
    }

    /* ------------------------------------------------------------------ */
    /* Render                                                            */
    /* ------------------------------------------------------------------ */

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        // background + the search field widget
        super.render(guiGraphics, mouseX, mouseY, partialTicks)
        val list = displayList()
        this.entries = list

        guiGraphics.drawString(this.font, "Available: " + list.size + "   |   Applied stack (top overrides)", 8, 7, 0xFFFFFF)
        val field = searchField
        if (field != null) {
            if (field.value.isEmpty() && !field.isFocused) {
                guiGraphics.drawString(this.font, "search...", field.x + 4, field.y + 4, 0x888888)
            }
        }

        drawChips(guiGraphics, mouseX, mouseY)

        if (list.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, if (scanDone) "No packs available." else "Scanning...", this.width / 2, this.height / 2 - 10, 0xFFFFFF)
        } else {
            drawThumbsIfReady()
            val top = listTop()
            val rows = visibleRows()

            // column backgrounds
            guiGraphics.fill(colLeftX() - 2, listTop() - 2, colLeftX() + COL_W + 2, listBottom() + 2, 0x22000000)
            guiGraphics.fill(colRightX() - 2, listTop() - 2, colRightX() + COL_W + 2, listBottom() + 2, 0x22000000)

            // LEFT: available
            val count = Math.min(rows, list.size - scrollAvailable)
            for (i in 0 until count) {
                val idx = scrollAvailable + i
                if (idx >= list.size) break
                drawRow(guiGraphics, list[idx], colLeftX(), top + i * ROW_H, i, mouseX, mouseY, false, selectedAvailable == idx)
            }

            // RIGHT: applied stack
            val st = appliedStack()
            val sc = Math.min(rows, st.size - scrollStack)
            for (i in 0 until sc) {
                val idx = scrollStack + i
                if (idx >= st.size) break
                drawRow(guiGraphics, st[idx], colRightX(), top + i * ROW_H, i, mouseX, mouseY, true, false)
            }
        }

        val s = status
        if (s != null && s.isNotEmpty()) {
            guiGraphics.drawCenteredString(this.font, s, this.width / 2, this.height - 44, 0xFFFF55)
        }

        // external Move up / Move down text buttons beside the right column
        val stL = appliedStack()
        val hasSel = selectedStack >= 0 && selectedStack < stL.size
        val canUp = hasSel && selectedStack > 0
        val canDown = hasSel && selectedStack < stL.size - 1
        val bw2 = 74
        val bh2 = 18
        val ux = colRightX() + COL_W + 8
        val uy = listTop()
        // Move up
        guiGraphics.fill(ux, uy, ux + bw2, uy + bh2, if (hasSel && canUp) 0xCC33AAFF.toInt() else 0x88222222.toInt())
        guiGraphics.drawCenteredString(this.font, "Move up", ux + bw2 / 2, uy + 5, if (canUp) 0xFFFFFF else 0x888888)
        // Move down
        guiGraphics.fill(ux, uy + bh2 + 4, ux + bw2, uy + bh2 + 4 + bh2, if (hasSel && canDown) 0xCC33AAFF.toInt() else 0x88222222.toInt())
        guiGraphics.drawCenteredString(this.font, "Move down", ux + bw2 / 2, uy + bh2 + 4 + 5, if (canDown) 0xFFFFFF else 0x888888)

        // flush debounced applies after 600ms idle
        // (debounce removed — double-click applies immediately)

        val bw = 64
        val bh = 20
        val gap = 5
        val labels = arrayOf("Apply", "Remove", "Revert All", "Legacy", "Body", "Done")
        val total = labels.size * bw + (labels.size - 1) * gap
        val bx = (this.width - total) / 2
        val by = this.height - 32
        for (li in labels.indices) {
            val x0 = bx + li * (bw + gap)
            guiGraphics.fill(x0, by, x0 + bw, by + bh, 0xAA222222.toInt())
            guiGraphics.drawCenteredString(this.font, labels[li], x0 + bw / 2, by + 6, 0xFFFFFF)
        }
    }

    private fun drawChips(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        var cx = 8
        var cy = 26
        chipRows = 1
        val chips = ArrayList<String?>()
        chips.add(null)
        for (g in ReskinScanner.GIRLS) chips.add(g)
        for (g in chips) {
            if (cx + CHIP_W > this.width - 4) {
                cx = 8
                cy += CHIP_H + 2
                chipRows++
            }
            val label = g?.uppercase() ?: "ALL"
            val active = (g == null && filterGirl == null) || (g != null && g == filterGirl)
            val bg = if (active) 0xCC33AAFF.toInt() else if (mouseX >= cx && mouseX <= cx + CHIP_W && mouseY >= cy && mouseY <= cy + CHIP_H) 0xAA444444.toInt() else 0x88222222.toInt()
            guiGraphics.fill(cx, cy, cx + CHIP_W, cy + CHIP_H, bg)
            guiGraphics.drawCenteredString(this.font, label, cx + CHIP_W / 2, cy + 3, if (active) 0xFFFFFFFF.toInt() else 0xCCCCCC)
            cx += CHIP_W + CHIP_GAP
        }
    }

    private fun drawRow(guiGraphics: GuiGraphics, e: ReskinEntry, colX: Int, y: Int, rowIdx: Int, mouseX: Int, mouseY: Int, applied: Boolean, selected: Boolean) {
        val zebra = rowIdx % 2 == 0
        guiGraphics.fill(colX, y, colX + COL_W, y + ROW_H, if (zebra) 0x55222222 else 0x33000000)
        if (selected) {
            guiGraphics.fill(colX - 1, y - 1, colX + COL_W + 1, y + ROW_H + 1, 0xFF33AAFF.toInt()) // highlight border
        }
        // icon 32x32
        val tex = thumbTextures[e.source.absolutePath]
        if (tex != null) {
            try {
                guiGraphics.blit(tex, colX + 2, y + 2, 0.0f, 0.0f, ICON, ICON, ICON, ICON)
            } catch (t: Throwable) {
                // ignored: the thumbnail texture is not uploaded yet; it is drawn on a later frame
            }
        } else {
            guiGraphics.fill(colX + 2, y + 2, colX + 2 + ICON, y + 2 + ICON, 0xFF333333.toInt())
        }

        // APPLIED (right) column: no overlay sprites; selection via double-click +
        // external Up/Down buttons beside the list (drawn in render).

        // name + girls
        guiGraphics.drawString(this.font, if (e.displayName.length > 16) e.displayName.substring(0, 15) + "…" else e.displayName, colX + 2 + ICON + 4, y + 4, 0xFFFFFF, false)
        // girls desc trimmed to fit the column width (no run-off under the zip name)
        var gi = e.girlSummary().uppercase()
        val maxW = COL_W - ICON - 6
        if (this.font.width(gi) > maxW) {
            gi = this.font.plainSubstrByWidth(gi, maxW - this.font.width("…")) + "…"
        }
        guiGraphics.drawString(this.font, gi, colX + 2 + ICON + 4, y + 16, 0xAAAAAA, false)
        if (applied) {
            val prio = if (rowIdx == 0) "[TOP]" else "[" + (rowIdx + 1) + "]"
            guiGraphics.drawString(this.font, prio, colX + COL_W - 30, y + 4, if (rowIdx == 0) 0x55FF55 else 0xAAAAAA, false)
        }
    }

    /** Convert buffered thumbs (decoded off-thread) into GL textures on the render thread. */
    private fun drawThumbsIfReady() {
        if (!thumbsDirty && pendingThumbs.isEmpty()) return
        synchronized(pendingThumbs) {
            for (en in pendingThumbs.entries) {
                try {
                    val k = en.key
                    if (thumbTextures.containsKey(k)) continue
                    val dt = DynamicTexture(ReskinManager.toNativeImage(en.value))
                    val rl = Minecraft.getInstance().textureManager.register("reskin_thumb_" + Integer.toHexString(k.hashCode()), dt)
                    thumbTextures[k] = rl
                } catch (t: Throwable) {
                    // unreadable image -> leave placeholder
                }
            }
            pendingThumbs.clear()
        }
        thumbsDirty = false
    }

    /**
     * Drops the fallback preview girls this screen created for the wardrobe /
     * body-slider hand-off. They are only stand-ins when no real girl of the
     * selected type exists, and must not stay in the registry after the screen
     * closes (the receiving screens hold their own reference).
     */
    override fun removed() {
        super.removed()
        for (preview in this.previewGirls) {
            preview.setLocallyRegistered(false)
        }
        this.previewGirls.clear()
    }

    companion object {
        private const val ROW_H = 36
        private const val ICON = 32
        private const val COL_W = 200
        private const val CHIP_W = 60
        private const val CHIP_H = 15
        private const val CHIP_GAP = 3
        private const val LIST_BOTTOM_MARGIN = 44

        /**
         * Routing helper used by the wand and K key: if there are no packs installed,
         * show the download prompt instead of an empty browser. Otherwise open the
         * browser (optionally pre-filtered to a girl).
         */
        @JvmStatic
        fun show(mc: Minecraft, girlFilter: String?) {
            try {
                val packs = ReskinManager.INSTANCE.scan()
                val empty = packs.isEmpty()
                if (empty) {
                    mc.setScreen(NoModelsScreen(girlFilter))
                } else {
                    mc.setScreen(ReskinBrowserScreen(girlFilter))
                }
            } catch (t: Throwable) {
                mc.setScreen(ReskinBrowserScreen(girlFilter))
            }
        }

        @JvmStatic
        fun debugHeadless(groupDir: String) {
            val found = ReskinScanner().scan(File(groupDir))
            Main.LOGGER.info("=== reskin scan of $groupDir ===")
            Main.LOGGER.info("archives found: " + found.size)
            var zip = 0
            var rar = 0
            for (e in found) {
                if (e.kind == ReskinEntry.Kind.ZIP) zip++ else rar++
            }
            Main.LOGGER.info("zips=$zip rars=$rar")
        }
    }
}
