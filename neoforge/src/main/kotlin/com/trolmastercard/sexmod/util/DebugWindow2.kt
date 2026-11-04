package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.language.I18n
import java.awt.BorderLayout
import java.awt.EventQueue
import java.awt.Font
import java.awt.SystemColor
import java.io.File
import java.io.FileWriter
import java.io.IOException
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.border.EmptyBorder

/**
 * Second developer debug overlay window (leftover, largely dormant): the
 * "are you 18" Swing prompt. CLIENT-ONLY.
 *
 * 1.21: `FileUtils.deleteDirectory` -> recursive `File.deleteRecursively`;
 * `FMLCommonHandler.exitJava(0, true)` -> `Minecraft.stop()` followed by a hard
 * `Runtime.halt(0)` (the 1.12 call skipped shutdown hooks too).
 */
open class DebugWindow2 : JFrame() {
    private val panel: JPanel

    init {
        this.isResizable = false
        this.setBounds(100, 100, 600, 260)
        this.panel = JPanel()
        this.panel.border = EmptyBorder(5, 5, 5, 5)
        this.panel.layout = BorderLayout(0, 0)
        this.contentPane = this.panel
        val northPanel = JPanel()
        this.panel.add(northPanel, "North")
        val titlePane = JTextPane()
        titlePane.font = Font("Tahoma", 0, 16)
        titlePane.background = SystemColor.control
        titlePane.text = I18n.get("window.pornwarning.title")
        northPanel.add(titlePane)
        val southPanel = JPanel()
        this.panel.add(southPanel, "South")
        val dontAskCheckbox = JCheckBox(I18n.get("window.pornwarning.dontaskagain"))
        southPanel.add(dontAskCheckbox)
        val am18Button = JButton(I18n.get("window.pornwarning.am18"))
        am18Button.addActionListener {
            isVisible = false
            if (dontAskCheckbox.isSelected) {
                val sexmodDir = File("sexmod")
                sexmodDir.mkdir()
                val dontAskFile = File("sexmod/dontAskAgain")

                try {
                    dontAskFile.createNewFile()
                } catch (ioException: IOException) {
                    Main.LOGGER.error("could not create sexmod/dontAskAgain", ioException)
                }
            }

            instance?.dispose()
        }
        southPanel.add(am18Button)
        val not18Button = JButton(I18n.get("window.pornwarning.not18"))
        not18Button.addActionListener {
            isVisible = false
            Main.LOGGER.info("MINOR!!! WHEOO WOOO WHEEE WHOOO WHEEE WHOO")
            val sexmodDir = File("sexmod")

            if (!sexmodDir.deleteRecursively()) {
                Main.LOGGER.error("could not delete the sexmod directory")
            }

            val deleteBat = File("mods/youCanJustDeleteMe.bat")

            try {
                val writer = FileWriter(deleteBat)
                writer.write("@echo off\n")
                writer.write("TIMEOUT /T 5\n")
                writer.write("DEL \"mods\\*sexmod*.jar\"\n")
                writer.write("exit 0")
                writer.close()
                Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", deleteBat.path))
            } catch (ioException: IOException) {
                Main.LOGGER.error("could not write or launch the mods cleanup script", ioException)
            }

            Minecraft.getInstance().stop()
            Runtime.getRuntime().halt(0)
        }
        southPanel.add(not18Button)
        val centerPanel = JPanel()
        this.panel.add(centerPanel, "Center")
        centerPanel.layout = BoxLayout(centerPanel, 0)
        val infoPane = JTextPane()
        infoPane.contentType = "text/html"
        infoPane.background = SystemColor.control
        infoPane.isEditable = false
        infoPane.text = "<html><center><p style='font-family: Tahoma'>" + I18n.get("window.pornwarning.text") + "</p></center></html> "
        centerPanel.add(infoPane)
    }

    companion object {
        @JvmField
        var instance: DebugWindow2? = null

        @JvmField
        var isVisible: Boolean = true

        @JvmStatic
        fun showDebugWindow() {
            EventQueue.invokeLater {
                try {
                    val window = DebugWindow2()
                    instance = window
                    window.isVisible = true
                    window.requestFocus()
                } catch (exception: Exception) {
                    Main.LOGGER.error("could not open the debug window", exception)
                }
            }
        }
    }
}
