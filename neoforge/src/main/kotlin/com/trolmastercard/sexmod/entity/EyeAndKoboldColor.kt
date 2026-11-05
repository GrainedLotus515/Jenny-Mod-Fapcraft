package com.trolmastercard.sexmod.entity

import net.minecraft.ChatFormatting
import net.minecraft.core.Vec3i

/**
 * <b>Role.</b> The shared kobold/goblin color palette: each entry packs a
 * main and secondary skin color, the matching wool metadata id and a chat
 * color. Used by the kobold/goblin entities for body/eye colors (stored as
 * [Vec3i] in data keys or by name) and by the tribe chat messages.
 *
 * <b>Pitfalls.</b> The enum order IS the persisted part-index order for
 * kobold/goblin customization (see `KoboldPlayerEntity.setCustomPartList`)
 * — never reorder. All "safe" lookups fall back to `KoboldEntity.DEFAULT_EYE_COLOR`
 * (the PURPLE kobold default) on unknown input.
 *
 * The wool "metadata" ids are the 1.12 wool meta values; after the Flattening
 * they map 1:1 onto `DyeColor.getId()` (0 = white ... 15 = black), so callers
 * translate a wool block / dye item through `DyeColor` and keep this int API.
 */
enum class EyeAndKoboldColor(
    mainColorR: Int,
    mainColorG: Int,
    mainColorB: Int,
    secondaryColorR: Int,
    secondaryColorG: Int,
    secondaryColorB: Int,
    private val woolMeta: Int,
    private val textColor: ChatFormatting,
) {
    GREEN(69, 141, 113, 91, 167, 128, 9, ChatFormatting.DARK_GREEN),
    YELLOW(241, 177, 77, 255, 226, 170, 4, ChatFormatting.YELLOW),
    RED(230, 27, 57, 253, 232, 239, 14, ChatFormatting.RED),
    PURPLE(196, 148, 207, 246, 188, 96, 10, ChatFormatting.DARK_PURPLE),
    LIGHT_GREEN(170, 208, 47, 230, 214, 104, 5, ChatFormatting.GREEN),
    OLD_BLUE(173, 138, 128, 118, 151, 180, 2, ChatFormatting.LIGHT_PURPLE),
    DARK_GREY(92, 92, 110, 198, 193, 165, 7, ChatFormatting.DARK_GRAY),
    BROWN(200, 145, 112, 253, 228, 198, 12, ChatFormatting.GOLD),
    DARK_BLUE(65, 84, 116, 104, 137, 146, 11, ChatFormatting.DARK_BLUE),
    LIGHT_BLUE(100, 163, 206, 138, 235, 242, 3, ChatFormatting.DARK_AQUA),
    SILVER(136, 136, 134, 255, 255, 255, 0, ChatFormatting.GRAY);

    private val mainColor: Vec3i = Vec3i(mainColorR, mainColorG, mainColorB)
    private val secondaryColor: Vec3i = Vec3i(secondaryColorR, secondaryColorG, secondaryColorB)

    fun getMainColor(): Vec3i = this.mainColor

    fun getSecondaryColor(): Vec3i = this.secondaryColor

    fun getWoolMeta(): Int = this.woolMeta

    fun getTextColor(): ChatFormatting = this.textColor

    companion object {
        @JvmStatic
        fun indexOf(color: EyeAndKoboldColor?): Int {
            var index = 0

            for (entry in values()) {
                if (color === entry) {
                    return index
                }

                index++
            }

            return index
        }

        @JvmStatic
        fun safeValueOf(name: String): EyeAndKoboldColor {
            return try {
                valueOf(name)
            } catch (ex: IllegalArgumentException) {
                KoboldEntity.DEFAULT_EYE_COLOR
            }
        }

        @JvmStatic
        fun safeValueOf(colorVec: Vec3i): EyeAndKoboldColor {
            for (entry in values()) {
                if (colorVec == entry.getMainColor()) {
                    return entry
                }
            }

            return KoboldEntity.DEFAULT_EYE_COLOR
        }

        @JvmStatic
        fun getColorByWoolId(woolMeta: Int): EyeAndKoboldColor {
            for (entry in values()) {
                if (entry.getWoolMeta() == woolMeta) {
                    return entry
                }
            }

            return KoboldEntity.DEFAULT_EYE_COLOR
        }
    }
}
