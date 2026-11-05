package com.trolmastercard.sexmod.reskin

import com.trolmastercard.sexmod.util.ServerWhitelistManager
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * **Role.** First-run / empty-folder prompt: shown when the user opens the
 * girl wand but no custom-model packs are installed. Asks
 * "No models found. Would you like to download some from github?" with
 * - **Download** — fetches the GitHub archive ([PackDownloader])
 *   on a background thread and extracts it into the instance's custom-models
 *   folder, then opens the pack browser prefilled with the newly installed
 *   packs.
 * - **View on Github** — opens the archive page in the system browser.
 * - **No** — closes. The prompt reappears on the next wand open until at
 *   least one pack exists in the directory (no persistent flag: "unless they
 *   have a model in the directory" is exactly the empty-scan check).
 *
 * CLIENT-ONLY.
 */
open class NoModelsScreen(private val girlFilter: String?) : Screen(Component.literal("No models")) {
    @Volatile
    private var status: String? = ""

    @Volatile
    private var working = false

    @Volatile
    private var clicked = false

    init {
        this.minecraft = Minecraft.getInstance()
    }

    override fun isPauseScreen(): Boolean = false

    override fun mouseClicked(mouseXd: Double, mouseYd: Double, mouseButton: Int): Boolean {
        val handled = super.mouseClicked(mouseXd, mouseYd, mouseButton)
        if (mouseButton != 0 || working || clicked) return handled
        val mouseX = mouseXd.toInt()
        val mouseY = mouseYd.toInt()

        // buttons: Download | View on Github | No
        val bw = 110
        val bh = 20
        val gap = 10
        val labels = arrayOf("Download", "View on Github", "No")
        val total = labels.size * bw + (labels.size - 1) * gap
        val bx = (this.width - total) / 2
        val by = this.height / 2 + 10
        for (i in labels.indices) {
            val x0 = bx + i * (bw + gap)
            if (mouseX >= x0 && mouseX <= x0 + bw && mouseY >= by && mouseY <= by + bh) {
                onButton(labels[i])
                return true
            }
        }
        return handled
    }

    private fun onButton(label: String) {
        clicked = true
        try {
            Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f))
        } catch (ignored: Throwable) {
            // ignored: a missing click sound must not break the screen
        }
        when (label) {
            "Download" -> startDownload()
            "View on Github" -> {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    try {
                        Desktop.getDesktop().browse(URI(PackDownloader.PAGE_URL))
                    } catch (e: Exception) {
                        status = "Could not open browser: " + e.javaClass.simpleName
                    }
                } else {
                    status = "Open manually: " + PackDownloader.PAGE_URL
                }
                clicked = false // allow other buttons again
            }
            else -> Minecraft.getInstance().setScreen(null)
        }
    }

    /** Downloads + extracts on a background thread, then opens the browser. */
    private fun startDownload() {
        working = true
        status = "Downloading models from GitHub..."
        ReskinManager.INSTANCE.submit {
            var result: String
            var zip: File? = null
            try {
                val dest = File(ServerWhitelistManager.getGlobalModelOverride())
                dest.mkdirs()
                zip = PackDownloader.downloadToTemp(PackDownloader.ARCHIVE_URL)
                result = PackDownloader.extractTo(zip, dest)
            } catch (t: Throwable) {
                result = "Download failed: " + t.javaClass.simpleName + (if (t.message == null) "" else ": " + t.message)
            } finally {
                zip?.delete()
            }
            val msg = result
            Minecraft.getInstance().execute {
                working = false
                try {
                    ReskinManager.INSTANCE.rescan()
                } catch (t: Throwable) {
                    // still open the browser below
                }
                if (msg.lowercase().contains("failed") || msg.lowercase().contains("missing") || msg.lowercase().contains("extraction failed")) {
                    status = msg
                    clicked = false
                } else {
                    // success: open the browser (it will show the newly installed packs)
                    Minecraft.getInstance().setScreen(ReskinBrowserScreen(girlFilter, msg))
                }
            }
        }
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTicks: Float) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks)
        guiGraphics.drawCenteredString(this.font, "No models found.", this.width / 2, this.height / 2 - 40, 0xFFFFFF)
        guiGraphics.drawCenteredString(this.font, "Would you like to download some from github?", this.width / 2, this.height / 2 - 24, 0xFFFFFF)

        val bw = 110
        val bh = 20
        val gap = 10
        val labels = arrayOf("Download", "View on Github", "No")
        val total = labels.size * bw + (labels.size - 1) * gap
        val bx = (this.width - total) / 2
        val by = this.height / 2 + 10
        for (i in labels.indices) {
            val x0 = bx + i * (bw + gap)
            val hover = mouseX >= x0 && mouseX <= x0 + bw && mouseY >= by && mouseY <= by + bh
            guiGraphics.fill(x0, by, x0 + bw, by + bh, if (working && i == 0) 0x66333333 else if (hover) 0xCC33AAFF.toInt() else 0xAA222222.toInt())
            guiGraphics.drawCenteredString(this.font, labels[i], x0 + bw / 2, by + 6, if (working && i == 0) 0x888888 else 0xFFFFFF)
        }

        val s = status
        if (s != null && s.isNotEmpty()) {
            guiGraphics.drawCenteredString(this.font, s, this.width / 2, this.height / 2 + 48, 0xFFFF55)
        }
    }
}
