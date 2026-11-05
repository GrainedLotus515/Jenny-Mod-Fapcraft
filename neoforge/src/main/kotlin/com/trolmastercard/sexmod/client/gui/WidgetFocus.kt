package com.trolmastercard.sexmod.client.gui

import net.minecraft.client.gui.screens.Screen

/**
 * The mod's screens rebuild their buttons every frame (1.12 cleared `buttonList` in
 * `drawScreen`). 1.21 screens keep the focused child across frames, and `clearWidgets`
 * leaves it pointing at a removed button, so Tab navigation and the focus highlight
 * broke. Screens record the focused child's index before rebuilding and hand focus
 * to the rebuilt widget at the same index afterwards. CLIENT-ONLY.
 */
object WidgetFocus {
    @JvmStatic
    fun focusedIndex(screen: Screen): Int {
        val focused = screen.focused ?: return -1
        return screen.children().indexOf(focused)
    }

    @JvmStatic
    fun restore(screen: Screen, index: Int) {
        val children = screen.children()
        screen.focused = if (index in children.indices) children[index] else null
    }
}
