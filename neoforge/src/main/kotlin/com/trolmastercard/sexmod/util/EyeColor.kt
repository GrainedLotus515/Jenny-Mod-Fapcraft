package com.trolmastercard.sexmod.util

import net.minecraft.core.Vec3i

/**
 * Eye colors of goblin NPCs/players, encoded as RGB triples.
 * Stored in the goblin DNA model-code string at index 8 ("eyeColor"/"eyeColor2" bones).
 */
enum class EyeColor(red: Int, green: Int, blue: Int) {
    RED(255, 0, 0),
    VIOLET(132, 30, 156),
    YELLOW(243, 247, 0),
    BROWN(105, 60, 9),
    TURKEY(0, 206, 217),
    BLUE(0, 0, 255);

    private val color: Vec3i = Vec3i(red, green, blue)

    /** @return the RGB color value of this eye color */
    fun getColor(): Vec3i = this.color

    companion object {
        /** @return the eye color matching the given RGB value, or RED if none matches */
        @JvmStatic
        fun fromColor(rgb: Vec3i): EyeColor {
            for (eyeColor in values()) {
                if (rgb == eyeColor.getColor()) {
                    return eyeColor
                }
            }
            return RED
        }

        /** @return the ordinal index of the given eye color within this enum */
        @JvmStatic
        fun indexOf(color: EyeColor?): Int {
            var colorIndex = 0
            for (value in values()) {
                if (color === value) {
                    return colorIndex
                }
                colorIndex++
            }
            return colorIndex
        }
    }
}
