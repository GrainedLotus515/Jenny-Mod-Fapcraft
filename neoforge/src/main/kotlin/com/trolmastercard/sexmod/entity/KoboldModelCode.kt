package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.Point2D
import net.minecraft.core.BlockPos

/**
 * Custom-model code ("DNA") for [KoboldEntity]: builds the padded
 * letter/number segment string, maps the editor's part-id list onto the
 * data-manager keys, and answers the atlas offsets of the elephant/eye parts.
 *
 * Part ids 0..2 are not DNA segments — they are body size (0), eye colour (1)
 * and main colour (2) and are written to `SIZE_SCALAR` / `CURRENT_ACTION` /
 * `ACTION_TARGET_POS`; every other index is appended as a padded number.
 * <b>Pitfalls.</b> The order of the first three ids is baked into saves through
 * `APPEARANCE_DNA`; `setCustomPartList` flipping `editedColorManually` when the
 * eye colour changes is what stops the tribe colour sync from overwriting a
 * manual edit.
 *
 * 1.21: the renderer bone-colour reset goes through [KoboldEntity.clearBoneColors]
 * (client-dist guarded) instead of calling `KoboldRenderer` directly.
 */
object KoboldModelCode {
    @JvmStatic
    fun buildModelCodeDNA(entity: KoboldEntity?, builder: StringBuilder): String {
        AbstractNpcOnlyEntity.appendPaddedLetter(builder, 8)
        AbstractNpcOnlyEntity.appendPaddedLetter(builder, 3)
        AbstractNpcOnlyEntity.appendRandomGene(builder)
        AbstractNpcOnlyEntity.appendRandomGene(builder)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 2)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 2)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 1)
        AbstractNpcOnlyEntity.appendPaddedNumber(builder, 1)
        return builder.toString()
    }

    @JvmStatic
    fun getCustomPartIdList(entity: KoboldEntity): ArrayList<Int> = arrayListOf(
        101,
        EyeAndKoboldColor.values().size,
        EyeAndKoboldColor.values().size,
        8,
        3,
        101,
        101,
        3,
        3,
        4,
        2,
    )

    @JvmStatic
    fun getBasePartIdList(entity: KoboldEntity): ArrayList<Int> {
        val parts = ArrayList<Int>()
        parts.add(Math.round(entity.getEntityData().get(KoboldEntity.SIZE_SCALAR) * 100.0f / 0.25f))
        parts.add(EyeAndKoboldColor.indexOf(EyeAndKoboldColor.safeValueOf(entity.getEntityData().get(KoboldEntity.CURRENT_ACTION))))
        parts.add(EyeAndKoboldColor.indexOf(EyeAndKoboldColor.safeValueOf(entity.getEntityData().get(KoboldEntity.ACTION_TARGET_POS))))
        return parts
    }

    @JvmStatic
    fun setCustomPartList(entity: KoboldEntity, parts: List<Int>) {
        val builder = StringBuilder()

        for (i in parts.indices) {
            val partId = parts[i]
            when (i) {
                0 -> entity.getEntityData().set(KoboldEntity.SIZE_SCALAR, partId / 100.0f * 0.25f)
                1 -> {
                    val currentColor = entity.getEntityData().get(KoboldEntity.CURRENT_ACTION)
                    val newColor = EyeAndKoboldColor.values()[partId].toString()
                    if (newColor != currentColor) {
                        entity.editedColorManually = true
                    }

                    entity.getEntityData().set(KoboldEntity.CURRENT_ACTION, newColor)
                }
                2 -> entity.getEntityData().set(KoboldEntity.ACTION_TARGET_POS, BlockPos(EyeAndKoboldColor.values()[partId].getMainColor()))
                else -> AbstractNpcOnlyEntity.appendPaddedNumber(builder, partId)
            }
        }

        entity.getEntityData().set(KoboldEntity.APPEARANCE_DNA, builder.toString())
        entity.clearBoneColors()
    }

    @JvmStatic
    fun updateModelCodeDNA(entity: KoboldEntity) {
        val partsData = entity.customPartsData
        if (partsData != null) {
            val prevDna = entity.getEntityData().get(KoboldEntity.APPEARANCE_DNA)
            val builder = StringBuilder()

            for (i in partsData.indices) {
                val entry = partsData[i]
                val partId = entry.value.value
                when (i) {
                    0 -> entity.getEntityData().set(KoboldEntity.SIZE_SCALAR, partId / 100.0f * 0.25f)
                    1 -> entity.getEntityData().set(KoboldEntity.CURRENT_ACTION, EyeAndKoboldColor.values()[partId].toString())
                    2 -> entity.getEntityData().set(KoboldEntity.ACTION_TARGET_POS, BlockPos(EyeAndKoboldColor.values()[partId].getMainColor()))
                    else -> AbstractNpcOnlyEntity.appendPaddedNumber(builder, partId)
                }
            }

            entity.getEntityData().set(KoboldEntity.APPEARANCE_DNA, builder.toString())
            val newDna = entity.getEntityData().get(KoboldEntity.APPEARANCE_DNA)
            if (newDna != prevDna) {
                Main.LOGGER.info("[sexmod-pack] KoboldEntity.updateModelCodeDNA '$prevDna' -> '$newDna'")
            }
            entity.clearBoneColors()
        }
    }

    @JvmStatic
    fun getModelPartByIndex(entity: KoboldEntity, index: Int): Point2D {
        return when (index) {
            0 -> Point2D(160, 0)
            1 -> Point2D(180, 0)
            2 -> Point2D(200, 0)
            3 -> Point2D(220, 0)
            4 -> Point2D(227, 20)
            5 -> Point2D(140, 40)
            6 -> Point2D(160, 40)
            7 -> Point2D(180, 40)
            8 -> Point2D(227, 40)
            9 -> Point2D(0, 130)
            10 -> Point2D(20, 130)
            else -> Point2D.ZERO
        }
    }
}
