package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.client.SexWorldClient
import java.util.UUID
import java.util.regex.Pattern
import net.minecraft.core.BlockPos
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.level.Level

/**
 * <b>Role.</b> Base class for the player-form kobold/goblin transformations
 * (`KoboldPlayerEntity`, `GoblinPlayerEntity`). Manages the
 * custom-model "DNA" string (the `APPEARANCE_DNA` twin of
 * [AbstractNpcOnlyEntity]) plus the three shared player-specific keys.
 *
 * <b>State.</b> [SKIN_COLOR_NAME] (1.12 id 119) = skin/body color name,
 * [EYE_COLOR] (120) = eye color as [BlockPos], [MODEL_CODE] (121) = model-code
 * DNA string. In 1.21 they are the hoisted [BaseGirlEntity] keys
 * `SHARED_STRING_119`/`SHARED_BLOCKPOS_120`/`SHARED_STRING_121`
 * (PORTING.md §2.6); this class sets the DNA default in its constructor and the
 * client twin is synced via [syncModelCodeClient].
 *
 * <b>Pitfalls.</b> The keys are shared with
 * [AbstractNpcOnlyEntity.CURRENT_ACTION]/`ACTION_TARGET_POS`/
 * `APPEARANCE_DNA` — both classes are never instantiated as the same
 * entity, but renderers read the NPC names on player forms. [syncModelCodeClient]
 * clears bone colors whenever the synced triplet changes, so the renderer
 * cache never shows stale skin/eye colors after a wardrobe edit.
 */
abstract class AbstractKoboldPlayerEntity : AbstractPlayerGirlEntity {
    @JvmField
    var customPartsPending: Boolean = true

    @JvmField
    var lastSkinColor: String? = null

    @JvmField
    var lastModelCode: String? = null

    @JvmField
    var lastEyeColor: BlockPos? = null

    protected constructor(type: EntityType<out AbstractKoboldPlayerEntity>, level: Level) : super(type, level) {
        this.initModelCodeDefault(level)
    }

    protected constructor(type: EntityType<out AbstractKoboldPlayerEntity>, level: Level, uuid: UUID) : super(type, level, uuid) {
        this.initModelCodeDefault(level)
    }

    /** 1.12 entityInit registered MODEL_CODE with this default (the key itself is hoisted). */
    private fun initModelCodeDefault(level: Level) {
        if (!level.isClientSide || !SexWorldClient.isPreloadWorld(level)) {
            this.entityData.set(MODEL_CODE, this.buildModelCodeDNA(StringBuilder()))
        }
    }

    abstract fun buildModelCodeDNA(builder: StringBuilder): String

    /**
     * CLIENT/SERVER: rebuilds the owner-bound custom part list once, when the
     * first update arrives. On the server the part list is loaded from the
     * owning player's persistent data (`"sexmod:GirlSpecific" + npc type`);
     * on the client the one-shot flag just gates `clearBoneColors()`.
     */
    override fun tick() {
        super.tick()
        this.syncModelCodeClient()
        if (this.customPartsPending) {
            if (this.level().isClientSide) {
                this.clearBoneColors()
                this.customPartsPending = true
            } else {
                val player = this.getOwnerPlayer()
                if (player != null) {
                    val modelCode = player.persistentData.getString("sexmod:GirlSpecific" + NpcType.getNpcType(this))
                    this.customPartsPending = false
                    if ("" != modelCode) {
                        this.setCustomPartList(decodePartIdList(modelCode))
                    }
                }
            }
        }
    }

    /**
     * CLIENT-side: watches the synced skin/eye/DNA triplet and clears the
     * renderer's bone-color cache whenever any of the three changed, then
     * caches the new values. Keeps the rendered kobold in sync with server
     * wardrobe edits.
     */
    open fun syncModelCodeClient() {
        if (this.level().isClientSide) {
            val modelCode = this.entityData.get(SKIN_COLOR_NAME)
            val boneColors = this.entityData.get(MODEL_CODE)
            val heldPos = this.entityData.get(EYE_COLOR)
            if (this.lastSkinColor == null) {
                this.lastSkinColor = modelCode
                this.lastModelCode = boneColors
                this.lastEyeColor = heldPos
            } else {
                if (this.lastModelCode != boneColors || this.lastSkinColor != modelCode || this.lastEyeColor != heldPos) {
                    this.clearBoneColors()
                }

                this.lastSkinColor = modelCode
                this.lastModelCode = boneColors
                this.lastEyeColor = heldPos
            }
        }
    }

    abstract fun clearBoneColors()

    companion object {
        /** Alias of [BaseGirlEntity.SHARED_STRING_119] (1.12 id 119). */
        @JvmField
        val SKIN_COLOR_NAME: EntityDataAccessor<String> = BaseGirlEntity.SHARED_STRING_119

        /** Alias of [BaseGirlEntity.SHARED_BLOCKPOS_120] (1.12 id 120). */
        @JvmField
        val EYE_COLOR: EntityDataAccessor<BlockPos> = BaseGirlEntity.SHARED_BLOCKPOS_120

        /** Alias of [BaseGirlEntity.SHARED_STRING_121] (1.12 id 121). */
        @JvmField
        val MODEL_CODE: EntityDataAccessor<String> = BaseGirlEntity.SHARED_STRING_121

        @JvmStatic
        fun createAttributes(): AttributeSupplier.Builder = AbstractPlayerGirlEntity.createAttributes()

        /** Java `String.split("-")` semantics (trailing empty segment dropped). */
        @JvmStatic
        fun getModelCodeParts(girl: BaseGirlEntity): Array<String> {
            return Pattern.compile("-").split(girl.getEntityData().get(MODEL_CODE))
        }
    }
}
