package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.SexWorldClient
import com.trolmastercard.sexmod.util.Reference
import java.util.regex.Pattern
import net.minecraft.core.BlockPos
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.level.Level

/**
 * <b>Role.</b> Base class for NPC-only girls that expose their appearance as a
 * packed model-code "DNA" string and a synced action/target pair — currently
 * `GoblinEntity` and `KoboldEntity`. This is the NPC twin of
 * [AbstractKoboldPlayerEntity] (which reuses the same three keys for
 * the player forms).
 *
 * <b>State.</b> [CURRENT_ACTION] (1.12 id 119) = skin/body color name,
 * [ACTION_TARGET_POS] (120) = eye color packed as [BlockPos],
 * [APPEARANCE_DNA] (121) = the model-code DNA string. In 1.21 the three keys
 * are the hoisted [BaseGirlEntity] keys `SHARED_STRING_119`,
 * `SHARED_BLOCKPOS_120` and `SHARED_STRING_121` (PORTING.md §2.6), defined
 * for every girl; this class sets the DNA default in its constructor.
 * [tickClientDataCheck] detects data-manager changes client-side and
 * clears the renderer's bone-color cache.
 *
 * <b>Pitfalls.</b> The keys are SHARED with
 * [AbstractKoboldPlayerEntity.SKIN_COLOR_NAME]/`EYE_COLOR`/`MODEL_CODE` —
 * change both classes together or synced wardrobe edits break. The DNA format
 * is a dash-separated, zero-padded number list (see [appendPaddedNumber]),
 * consumed by [getModelCodeParts] and the renderer; `GoblinEntity`
 * appends a trailing segment (index 9) that holds the model-part index.
 */
abstract class AbstractNpcOnlyEntity : BaseGirlEntity {
    @JvmField
    var lastCachedAction: String? = null

    @JvmField
    var lastCachedDNA: String? = null

    @JvmField
    var lastCachedTargetPos: BlockPos? = null

    protected constructor(type: EntityType<out AbstractNpcOnlyEntity>, level: Level) : super(type, level) {
        // 1.12 entityInit registered APPEARANCE_DNA with this default (the key itself is hoisted)
        if (!level.isClientSide || !SexWorldClient.isPreloadWorld(level)) {
            this.entityData.set(APPEARANCE_DNA, this.buildModelCodeDNA(StringBuilder()))
        }
    }

    override fun tick() {
        super.tick()
        this.tickClientDataCheck()
    }

    /**
     * CLIENT-side: caches the synced action/DNA/target triplet and clears the
     * renderer's bone-color cache whenever any element changes. Prevents stale
     * colors after skin/eye/wardrobe edits on the server.
     */
    open fun tickClientDataCheck() {
        if (this.level().isClientSide) {
            val action = this.entityData.get(CURRENT_ACTION)
            val dna = this.entityData.get(APPEARANCE_DNA)
            val targetPos = this.entityData.get(ACTION_TARGET_POS)
            if (this.lastCachedAction == null) {
                this.lastCachedAction = action
                this.lastCachedDNA = dna
                this.lastCachedTargetPos = targetPos
            } else {
                if (this.lastCachedDNA != dna || this.lastCachedAction != action || this.lastCachedTargetPos != targetPos) {
                    this.clearBoneColors()
                }

                this.lastCachedAction = action
                this.lastCachedDNA = dna
                this.lastCachedTargetPos = targetPos
            }
        }
    }

    abstract fun clearBoneColors()

    abstract fun buildModelCodeDNA(builder: StringBuilder): String

    companion object {
        /** Alias of [BaseGirlEntity.SHARED_STRING_119] (1.12 id 119). */
        @JvmField
        val CURRENT_ACTION: EntityDataAccessor<String> = BaseGirlEntity.SHARED_STRING_119

        /** Alias of [BaseGirlEntity.SHARED_BLOCKPOS_120] (1.12 id 120). */
        @JvmField
        val ACTION_TARGET_POS: EntityDataAccessor<BlockPos> = BaseGirlEntity.SHARED_BLOCKPOS_120

        /** Alias of [BaseGirlEntity.SHARED_STRING_121] (1.12 id 121). */
        @JvmField
        val APPEARANCE_DNA: EntityDataAccessor<String> = BaseGirlEntity.SHARED_STRING_121

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = BaseGirlEntity.createAttributes()

        /**
         * Appends `value` as a zero-padded (2-digit) dash-terminated segment
         * of the model-code DNA string. Keeps every DNA segment a fixed width so
         * the string can be split by `"-"` and indexed positionally.
         */
        @JvmStatic
        fun appendPaddedNumber(builder: StringBuilder, number: Int) {
            if (number < 10) {
                builder.append(0)
            }

            builder.append(number)
            builder.append("-")
        }

        /**
         * Appends a uniformly-random segment in `[0, bound]` as a padded
         * dash-terminated DNA segment (used for randomization of trait ranges).
         */
        @JvmStatic
        fun appendRandomSegment(builder: StringBuilder, bound: Int) {
            val digit = Reference.RANDOM.nextInt(bound + 1)
            if (digit < 10) {
                builder.append(0)
            }

            builder.append(digit)
            builder.append("-")
        }

        /**
         * Appends a Gaussian-weighted gene segment: samples a value from a
         * bell-curve over [-2.5, 2.5] and writes its two fractional digits as the
         * DNA segment (drives naturally-distributed trait values like size).
         */
        @JvmStatic
        fun appendRandomGene(builder: StringBuilder) {
            val weight = Reference.RANDOM.nextDouble()
            val geneValue = Math.pow(Math.E, -Math.pow(-2.5 + 5.0 * weight, 2.0))
            var formatted = String.format("%.2f", geneValue)
            var parts = Pattern.compile("\\.").split(formatted)
            if (parts.size < 2) {
                parts = Pattern.compile(",").split(formatted)
            }

            formatted = parts[1]
            builder.append(formatted).append("-")
        }

        @JvmStatic
        fun appendPaddedLetter(builder: StringBuilder, bound: Int) {
            val digit = Reference.RANDOM.nextInt(bound)
            if (digit < 10) {
                builder.append(0)
            }

            builder.append(digit)
            builder.append("-")
        }

        /** Java `String.split("-")` semantics (trailing empty segment dropped). */
        @JvmStatic
        fun getModelCodeParts(girl: BaseGirlEntity): Array<String> {
            return Pattern.compile("-").split(girl.getEntityData().get(APPEARANCE_DNA))
        }
    }
}
