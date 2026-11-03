package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.util.Reference
import net.minecraft.world.entity.Entity

/**
 * <b>Role.</b> The canonical registry mapping every girl type to its NPC and
 * player-form classes plus their entity ids. [getNpcType] resolves the type
 * of any girl entity; the npc/player id pairs were used by the 1.12
 * [SexModEntities] registration and by the "sexmod:GirlSpecific" /
 * "sexmod:CustomModel" per-player data keys (suffixed with the npc type name).
 *
 * <b>Pitfalls.</b> The `npcID`/`playerID` values are the old persisted entity
 * ids — do not renumber (1.21 registers by resource id, the ints are kept for
 * call sites). [getNpcTypeByName] falls back to [JENNY] for unknown names.
 * [MANGLELIE] is NPC-only (`isNpcOnly`); KOBOLD and GOBLIN set `hasSpecifics`.
 */
enum class NpcType {
    JENNY(JennyEntity::class.java, 177013, JennyPlayerEntity::class.java, 12388645),
    ELLIE(EllieEntity::class.java, 228922, ElliePlayerEntity::class.java, 46348348),
    BIA(BiaEntity::class.java, 230053, BiaPlayerEntity::class.java, 65456415),
    SLIME(SlimeEntity::class.java, 168597, SlimePlayerEntity::class.java, 54816432),
    BEE(BeeEntity::class.java, 4663354, BeePlayerEntity::class.java, 48648638),
    ALLIE(AllieEntity::class.java, 5614613, AlliePlayerEntity::class.java, 64867483),
    LUNA(LunaEntity::class.java, 6816463, LunaPlayerEntity::class.java, 81234824),
    KOBOLD(KoboldEntity::class.java, 5648456, KoboldPlayerEntity::class.java, 62484851, true),
    GOBLIN(GoblinEntity::class.java, 4567275, GoblinPlayerEntity::class.java, 6584344, true),
    GALATH(GalathEntity::class.java, 314351, GalathPlayerEntity::class.java, 652535516),
    MANGLELIE(ManglelieEntity::class.java, 618151);

    @JvmField
    val npcID: Int

    @JvmField
    val playerID: Int

    @JvmField
    val npcClass: Class<out BaseGirlEntity>

    @JvmField
    val playerClass: Class<out AbstractPlayerGirlEntity>?

    @JvmField
    val isNpcOnly: Boolean

    @JvmField
    val editorID: Int

    @JvmField
    val hasSpecifics: Boolean

    constructor(
        npcClass: Class<out BaseGirlEntity>,
        npcID: Int,
        playerClass: Class<out AbstractPlayerGirlEntity>,
        playerID: Int,
        hasSpecifics: Boolean,
    ) {
        this.npcID = npcID
        this.playerID = playerID
        this.npcClass = npcClass
        this.playerClass = playerClass
        this.isNpcOnly = false
        this.hasSpecifics = hasSpecifics
        this.editorID = Reference.EDITOR_ID_COUNTER++
    }

    constructor(
        npcClass: Class<out BaseGirlEntity>,
        npcID: Int,
        playerClass: Class<out AbstractPlayerGirlEntity>,
        playerID: Int,
    ) {
        this.npcID = npcID
        this.playerID = playerID
        this.npcClass = npcClass
        this.playerClass = playerClass
        this.isNpcOnly = false
        this.hasSpecifics = false
        this.editorID = Reference.EDITOR_ID_COUNTER++
    }

    constructor(npcClass: Class<out BaseGirlEntity>, npcID: Int) {
        this.npcID = npcID
        this.npcClass = npcClass
        this.isNpcOnly = true
        this.hasSpecifics = false
        this.editorID = Reference.EDITOR_ID_COUNTER++
        this.playerClass = null
        this.playerID = 0
    }

    companion object {
        @JvmStatic
        fun getNpcTypeByName(name: String?): NpcType {
            for (type in values()) {
                if (type.toString().equals(name, ignoreCase = true)) {
                    return type
                }
            }

            return JENNY
        }

        @JvmStatic
        fun getNpcType(entity: Entity?): NpcType? {
            if (entity !is BaseGirlEntity) {
                return null
            }

            val clazz: Class<*> = entity.javaClass

            for (type in values()) {
                if (clazz == type.npcClass) {
                    return type
                }

                if (clazz == type.playerClass) {
                    return type
                }
            }

            return null
        }
    }
}
