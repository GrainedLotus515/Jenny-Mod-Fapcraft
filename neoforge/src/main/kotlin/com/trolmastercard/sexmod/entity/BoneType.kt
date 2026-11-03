package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.util.Reference

/**
 * <b>Role.</b> Classifies girl model bones for the custom-model system:
 * which bones are part-specific (head, feet, hands) and which are fully
 * custom ([CUSTOM_BONE]). Also owns the icon sprite X positions used by
 * the wardrobe GUI and allocates the +/- button ids via
 * [Reference.BUTTON_ID_COUNTER].
 *
 * <b>Pitfalls.</b> The enum declaration ORDER matters: [GIRL_SPECIFIC]
 * must stay first (it has no bone name) and [getCustomBoneCount]
 * assumes the last two entries are the custom ones. The button ids are
 * allocated at class-load time — do not reorder the enum or existing saves
 * map to different buttons.
 */
enum class BoneType {
    GIRL_SPECIFIC,
    HEAD(0, "customHead"),
    FOOT_L(60, "customShoeL"),
    FOOT_R(80, "customShoeR"),
    HAND_L(100, "customHandL"),
    HAND_R(120, "customHandR"),
    CUSTOM_BONE(140);

    @JvmField
    var buttonIDPlus: Int = 0

    @JvmField
    var buttonIDMinus: Int = 0

    @JvmField
    var boneName: String? = null

    @JvmField
    var iconXPos: Int = 0

    constructor()

    constructor(iconXPos: Int) {
        this.iconXPos = iconXPos
    }

    constructor(iconXPos: Int, boneName: String) {
        this.iconXPos = iconXPos
        this.boneName = boneName
        this.buttonIDPlus = ++Reference.BUTTON_ID_COUNTER
        this.buttonIDMinus = ++Reference.BUTTON_ID_COUNTER
    }

    companion object {
        const val SEPARATOR = "#"

        @JvmStatic
        fun getCustomBoneCount(): Int = values().size - 2
    }
}
