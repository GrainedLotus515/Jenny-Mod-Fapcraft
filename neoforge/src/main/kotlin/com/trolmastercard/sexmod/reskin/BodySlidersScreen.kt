package com.trolmastercard.sexmod.reskin

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.BoneType
import com.trolmastercard.sexmod.entity.NpcType
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.UploadModelStringPacket
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.AbstractMap.SimpleEntry
import java.util.UUID

/**
 * **Role.** Body/character sliders. Mirrors vanilla slider widgets: each row
 * has a 0..1 sliderPosition, drag clamps + updates, the thumb is drawn from
 * `position * trackWidth`. Each part shows its REAL NAME, current option
 * index and total count.
 *
 * **NPE-proof.** This screen never depends on the girl being in any registry:
 * it reads part counts from `getCustomPartIdList()` and keeps its own
 * `positions[]`; initial values are read once via a guarded
 * `buildCustomPartsData` (which is now registry-independent too). Writers
 * go through `setCustomPartsData` with a fresh well-formed list.
 *
 * CLIENT-ONLY. 1.21: `mouseClickMove` -> [mouseDragged], wheel -> [mouseScrolled].
 */
open class BodySlidersScreen(private val girl: BaseGirlEntity) : Screen(Component.literal("Body sliders")) {
    private val partCounts: MutableList<Int> = ArrayList()
    private val partNames: MutableList<String> = ArrayList()
    private val positions: FloatArray // 0..1 per part
    private val girlId: UUID = girl.getGirlId()
    private val modelCode: String = girl.getCustomModelCode()
    private var selected = 0
    private var scroll = 0
    private var dragging = false
    private var status: String? = ""

    init {
        this.minecraft = Minecraft.getInstance()
        try {
            val ids: List<*> = girl.getCustomPartIdList()
            for (o in ids) partCounts.add((o as Number).toInt())
        } catch (t: Throwable) {
            partCounts.clear()
        }
        for (i in partCounts.indices) {
            partNames.add(partNameFor(girl, i))
        }
        positions = FloatArray(partCounts.size)
        // initial values (guarded; falls back to 0)
        try {
            val data: List<*> = girl.buildCustomPartsData(girl.getGirlId())
            for (i in positions.indices) {
                if (i < data.size) {
                    val item = data[i]
                    if (item is Map.Entry<*, *>) {
                        val v0 = item.value
                        if (v0 is Map.Entry<*, *>) {
                            val v = v0.value
                            if (v is Int) {
                                positions[i] = posFromOption(v, optionCount(i))
                                continue
                            }
                        } else if (v0 is Int) {
                            positions[i] = posFromOption(v0, optionCount(i))
                            continue
                        }
                    }
                }
                positions[i] = 0f
            }
        } catch (t: Throwable) {
            // leave 0 defaults
        }
        if (positions.isNotEmpty()) selected = 0
    }

    private fun optionFromPos(row: Int): Int {
        val max = optionCount(row)
        if (max <= 1) return 0
        return Math.round(positions[row] * (max - 1))
    }

    private fun optionCount(row: Int): Int {
        if (row < 0 || row >= partCounts.size) return 1
        return Math.max(1, partCounts[row])
    }

    private fun partNameFor(girl: BaseGirlEntity, idx: Int): String {
        val type = NpcType.getNpcType(girl)?.name ?: ""
        if ("GOBLIN" == type) {
            // Verified against GoblinRenderer.onBoneProcessing + getBoneColor:
            // [0]=head variant (shared by ears), [1]/[2]=left/right ear variant,
            // [3]/[4]=ear variant color-group seeds, [5]=hair variant,
            // [6]=hair color, [7]=skin color, [8]=eye color.
            val names = arrayOf("Head", "Left Ear", "Right Ear", "Ear Pattern L", "Ear Pattern R", "Hairstyle", "Hair Color", "Skin Color", "Eye Color")
            if (idx < names.size) return names[idx]
        } else if ("KOBOLD" == type) {
            // Verified against KoboldNpcModel.setLivingAnimations + KoboldRenderer:
            // [0]=body size (aE), [1]/[2]=pattern/markings colors,
            // [3]=up-horn variant, [4]=down-horn variant, [5]=breast scale
            // (boobL/boobR/armorBoobs — this was mislabeled 'Horn'),
            // [6]=eye size/spacing, [7]=arm freckles, [8]=head freckles,
            // [9]=backpack/tailpack pose, [10]=mouth variant.
            val names = arrayOf("Height", "Pattern", "Markings", "Up Horns", "Down Horns", "Breasts", "Eyes", "Arm Freckles", "Head Freckles", "Backpack/Tailpack", "Mouth")
            if (idx < names.size) return names[idx]
        } else if ("JENNY" == type || "ELLIE" == type) {
            val names = arrayOf("Outfit", "Hair Color", "Eye Color", "Skin Tone", "Breasts", "Hips", "Legs", "Feet", "Tail", "Accessory")
            if (idx < names.size) return names[idx]
        }
        return "Part " + (idx + 1)
    }

    override fun isPauseScreen(): Boolean = false

    /* ------------------------------------------------------------------ */

    private fun rowTop(row: Int): Int = 30 + (row - scroll) * 26

    private fun visibleRows(): Int = Math.max(1, (this.height - 60) / 26)

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        val dw = Math.signum(scrollY).toInt()
        if (dw != 0) {
            scroll -= dw
            scroll = Math.max(0, Math.min(scroll, Math.max(0, partCounts.size - visibleRows())))
        }
        return true
    }

    override fun mouseClicked(mouseXd: Double, mouseYd: Double, mouseButton: Int): Boolean {
        val handled = super.mouseClicked(mouseXd, mouseYd, mouseButton)
        if (mouseButton != 0) return handled
        val mouseX = mouseXd.toInt()
        val mouseY = mouseYd.toInt()

        // Done (works even when there are no adjustable parts — it just closes)
        val bw = 60
        val bh = 20
        val bx = this.width - bw - 8
        val by = this.height - 30
        if (mouseX >= bx && mouseX <= bx + bw && mouseY >= by && mouseY <= by + bh) {
            applyToGirl()
            Minecraft.getInstance().setScreen(null)
            return true
        }

        if (partCounts.isEmpty()) return handled

        val top = 30
        val rows = visibleRows()
        val trackX = 160
        val trackW = this.width - trackX - 40
        for (r in 0 until rows) {
            val row = scroll + r
            if (row >= partCounts.size) break
            val ty = top + r * 26
            // label click = select
            if (mouseY >= ty && mouseY <= ty + 20 && mouseX >= 12 && mouseX <= 140) {
                selected = row
                return true
            }
            // track click/drag start
            if (mouseY >= ty && mouseY <= ty + 20 && mouseX >= trackX && mouseX <= trackX + trackW) {
                selected = row
                dragging = true
                setPositionFromMouse(row, mouseX, trackX, trackW)
                return true
            }
        }
        return handled
    }

    override fun mouseDragged(mouseXd: Double, mouseYd: Double, mouseButton: Int, dragX: Double, dragY: Double): Boolean {
        super.mouseDragged(mouseXd, mouseYd, mouseButton, dragX, dragY)
        val mouseX = mouseXd.toInt()
        val mouseY = mouseYd.toInt()
        if (dragging && mouseButton == 0 && selected >= 0 && selected < partCounts.size) {
            val ty = rowTop(selected)
            val trackX = 160
            val trackW = this.width - trackX - 40
            if (mouseY >= ty - 8 && mouseY <= ty + 28) {
                setPositionFromMouse(selected, mouseX, trackX, trackW)
            }
        }
        return true
    }

    override fun mouseReleased(mouseX: Double, mouseY: Double, mouseButton: Int): Boolean {
        super.mouseReleased(mouseX, mouseY, mouseButton)
        if (mouseButton == 0) dragging = false
        return true
    }

    private fun setPositionFromMouse(row: Int, mouseX: Int, trackX: Int, trackW: Int) {
        val pos = (mouseX - trackX) / Math.max(1, trackW).toFloat()
        positions[row] = Math.max(0f, Math.min(1f, pos))
        val opt = optionFromPos(row)
        val max = optionCount(row)
        status = partNames[row] + " -> option " + (opt + 1) + "/" + max
        writeToGirl(row, opt)
    }

    /** Push the option to the girl using a fresh well-formed customPartsData list. */
    private fun writeToGirl(row: Int, opt: Int) {
        try {
            val data: List<*> = girl.buildCustomPartsData(girl.getGirlId())
            val fresh = ArrayList<Map.Entry<BoneType, Map.Entry<List<String>, Int>>>()
            val size = Math.max(partCounts.size, data.size)
            for (i in 0 until size) {
                val count = if (i < partCounts.size) Math.max(1, partCounts[i]) else 1
                val v: Int
                if (i == row) {
                    v = Math.max(0, Math.min(opt, count - 1))
                } else if (i < data.size) {
                    // PRESERVE the other parts' current values. Zeroing them made the
                    // girl's other features revert the moment updateModelCodeDNA rebuilt
                    // her DNA from customPartsData (live preview + confirm).
                    val item = data[i]
                    var value: Any? = if (item is Map.Entry<*, *>) item.value else null
                    if (value is Map.Entry<*, *>) value = value.value
                    v = if (value is Int) Math.max(0, Math.min(value, count - 1)) else 0
                } else {
                    v = 0
                }
                fresh.add(SimpleEntry(BoneType.GIRL_SPECIFIC, SimpleEntry<List<String>, Int>(ArrayList(), v)))
            }
            girl.setCustomPartsData(fresh)
        } catch (t: Throwable) {
            // display-only feature; the preview keeps moving regardless
        }
    }

    /** Persist the current slider options to the (real) girl via UploadModelStringPacket. */
    private fun applyToGirl() {
        try {
            if (partCounts.isEmpty()) return
            val partIds = ArrayList<Int>()
            for (i in partCounts.indices) {
                partIds.add(optionFromPos(i))
            }
            PacketHandler.networkWrapper.sendToServer(UploadModelStringPacket(modelCode, girlId, partIds))
            status = "Applied " + partCounts.size + " part option(s)."
        } catch (t: Throwable) {
            status = "Could not apply: " + t.javaClass.simpleName
        }
    }

    /* ------------------------------------------------------------------ */

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks)

        var girlName: String
        girlName = try {
            val dn: String? = girl.getDisplayNameText()
            if (dn == null || dn.isEmpty()) girl.javaClass.simpleName else dn
        } catch (t: Throwable) {
            girl.javaClass.simpleName
        }
        val type = NpcType.getNpcType(girl)?.name ?: ""
        guiGraphics.drawCenteredString(this.font, "$girlName ($type) — character parts", this.width / 2, 8, 0xFFFFFF)

        val top = 30
        val rows = visibleRows()
        for (r in 0 until rows) {
            val row = scroll + r
            if (row >= partCounts.size) break
            val ty = top + r * 26
            val trackX = 160
            val trackW = this.width - trackX - 40
            val opt = optionFromPos(row)
            val max = optionCount(row)
            val sel = row == selected

            guiGraphics.fill(4, ty, this.width - 4, ty + 22, if (sel) 0x8033AAFF.toInt() else if (r % 2 == 0) 0x44222222 else 0x22000000)
            guiGraphics.drawString(this.font, partNames[row], 12, ty + 4, if (sel) 0xFFFF55 else 0xFFFFFF, false)
            guiGraphics.drawString(this.font, "option " + (opt + 1) + "/" + max, 12, ty + 13, 0xAAAAAA, false)

            val fill = Math.round(trackW * positions[row])
            guiGraphics.fill(trackX, ty + 9, trackX + trackW, ty + 12, 0xFF333333.toInt())
            if (fill > 0) guiGraphics.fill(trackX, ty + 9, trackX + fill, ty + 12, 0xFF33AAFF.toInt())
            val thumbX = trackX + fill - 2
            guiGraphics.fill(thumbX, ty + 6, thumbX + 4, ty + 15, if (sel) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt())
        }

        if (partCounts.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, "This character has no adjustable parts.", this.width / 2, 60, 0x888888)
        }
        val s = status
        if (s != null && s.isNotEmpty()) {
            guiGraphics.drawCenteredString(this.font, s, this.width / 2, this.height - 42, 0xFFFF55)
        }

        val bw = 60
        val bh = 20
        val bx = this.width - bw - 8
        val by = this.height - 30
        guiGraphics.fill(bx, by, bx + bw, by + bh, 0xAA222222.toInt())
        guiGraphics.drawCenteredString(this.font, "Done", bx + bw / 2, by + 6, 0xFFFFFF)
    }

    companion object {
        /** 0..1 position for an option index (0..max-1). */
        private fun posFromOption(option: Int, max: Int): Float {
            if (max <= 1) return 1f
            return Math.max(0f, Math.min(1f, option / (max - 1).toFloat()))
        }
    }
}
