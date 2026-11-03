package com.trolmastercard.sexmod.util

import net.minecraft.core.Vec3i

/**
 * Hair colors of goblin NPCs/players, encoded as RGB triples.
 * Stored in the goblin DNA model-code string at index 6 ("hair" bones).
 */
enum class HairColor(red: Int, green: Int, blue: Int) {
    PURPLE(103, 39, 123),
    ORANGE(251, 153, 56),
    BLACK(30, 33, 38),
    BLUE(88, 83, 186),
    BROWN(63, 35, 34),
    PINK(247, 102, 109),
    RED(241, 69, 49),
    GREEN(75, 143, 106);

    private val color: Vec3i = Vec3i(red, green, blue)

    /** @return the RGB color value of this hair color */
    fun getColor(): Vec3i = this.color

    companion object {
        /** @return the ordinal index of the given hair color within this enum */
        @JvmStatic
        fun indexOf(color: HairColor?): Int {
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
