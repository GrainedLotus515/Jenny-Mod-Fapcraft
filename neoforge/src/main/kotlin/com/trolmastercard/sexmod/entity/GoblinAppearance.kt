package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.api.SkinColor
import com.trolmastercard.sexmod.util.EyeColor
import com.trolmastercard.sexmod.util.HairColor
import com.trolmastercard.sexmod.util.Point2D

/**
 * Appearance/DNA packing for [GoblinEntity]: `APPEARANCE_DNA` is a
 * run of zero-padded part ids (model part sizes, hair/skin/eye bounds) with
 * the trailing segment (index 9) holding the model part index. Every setter
 * here rewrites the whole code and resets the renderer bone colours, because
 * the renderer caches per-bone colours keyed on that code.
 *
 * 1.21: `Main.proxy instanceof ClientProxy` / the direct `GoblinRenderer` call
 * are [GoblinEntity.clearBoneColors] (client-dist guarded).
 */
object GoblinAppearance {
    @JvmStatic
    fun buildModelCodeDNA(builder: StringBuilder): String {
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 3)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 2)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 2)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 8)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 8)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 5)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, HairColor.values().size - 1)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, SkinColor.values().size - 1)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, EyeColor.values().size - 1)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 0)
        return builder.toString()
    }

    @JvmStatic
    fun getCustomPartIdList(): ArrayList<Int> = arrayListOf(
        4,
        3,
        3,
        16,
        16,
        6,
        HairColor.values().size,
        SkinColor.values().size,
        EyeColor.values().size,
    )

    @JvmStatic
    fun getCustomPartExtraIdList(): List<Int> = listOf(2)

    @JvmStatic
    fun getModelPartByIndex(index: Int): Point2D {
        return when (index) {
            0 -> Point2D(40, 130)
            1 -> Point2D(60, 130)
            2 -> Point2D(80, 130)
            3 -> Point2D(100, 130)
            4 -> Point2D(120, 130)
            5 -> Point2D(140, 130)
            6 -> Point2D(160, 130)
            7 -> Point2D(180, 130)
            8 -> Point2D(200, 0)
            9 -> Point2D(200, 130)
            else -> Point2D.ZERO
        }
    }

    @JvmStatic
    fun setCustomPartList(goblin: GoblinEntity, parts: List<Int>) {
        val builder = StringBuilder()

        for (partId in parts) {
            AbstractNpcOnlyEntity.appendPaddedNumber(builder, partId)
        }

        AbstractNpcOnlyEntity.appendPaddedNumber(builder, AbstractNpcOnlyEntity.getModelCodeParts(goblin)[9].toInt())
        goblin.getEntityData().set(AbstractNpcOnlyEntity.APPEARANCE_DNA, builder.toString())
        goblin.clearBoneColors()
    }

    @JvmStatic
    fun updateModelCodeDNA(goblin: GoblinEntity) {
        val partsData = goblin.customPartsData
        if (partsData != null) {
            val builder = StringBuilder()

            for (entry in partsData) {
                val partId = entry.value.value
                AbstractNpcOnlyEntity.appendPaddedNumber(builder, partId)
            }

            AbstractNpcOnlyEntity.appendPaddedNumber(builder, AbstractNpcOnlyEntity.getModelCodeParts(goblin)[9].toInt())
            goblin.getEntityData().set(AbstractNpcOnlyEntity.APPEARANCE_DNA, builder.toString())
            goblin.clearBoneColors()
        }
    }

    @JvmStatic
    fun buildModelCodeDNA(builder: StringBuilder, partIndex: Int): String {
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 3)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 2)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 2)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 7)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 7)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 5)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, HairColor.values().size - 1)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, partIndex)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, EyeColor.values().size - 1)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 0)
        return builder.toString()
    }
}
