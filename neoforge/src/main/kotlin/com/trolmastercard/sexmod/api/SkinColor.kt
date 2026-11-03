package com.trolmastercard.sexmod.api

import net.minecraft.core.Vec3i

/**
 * Skin colors of goblin NPCs/players, encoded as RGB triples.
 * Stored in the goblin DNA model-code string at index 7 ("variant"/"boob" bones).
 */
enum class SkinColor(red: Int, green: Int, blue: Int) {
    LIGHT_GREEN(213, 239, 150),
    MEDIUM_GREEN(189, 165, 91),
    DARK_GREEN(160, 183, 135),
    LIGHT_YELLOW(234, 176, 102),
    LIGHT_BLUE(187, 203, 252);

    private val color: Vec3i = Vec3i(red, green, blue)

    /** @return the RGB color value of this skin tone */
    fun getColor(): Vec3i = this.color

    companion object {
        /** @return the ordinal index of the given skin color within this enum */
        @JvmStatic
        fun indexOf(color: SkinColor?): Int {
            var colorIndex = 0
            for (value in entries) {
                if (color == value) {
                    return colorIndex
                }
                colorIndex++
            }
            return colorIndex
        }
    }
}
