package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.Vec3

/**
 * NBT persistence for [GoblinEntity]. The goblin-specific half of
 * `addAdditionalSaveData`/`readAdditionalSaveData`; the entity keeps the
 * `super` calls and delegates here.
 *
 * Queen-only fields are written only when `isQueen` is set, and guards
 * are stored as `guard0`, `guard1`, ... read back until the first
 * empty entry.
 */
object GoblinPersistence {
    @JvmStatic
    fun write(goblin: GoblinEntity, nbt: CompoundTag) {
        val data = goblin.getEntityData()
        nbt.putString("bodyColor", data.get(AbstractNpcOnlyEntity.CURRENT_ACTION))
        nbt.putInt("eyeColorX", data.get(AbstractNpcOnlyEntity.ACTION_TARGET_POS).x)
        nbt.putInt("eyeColorY", data.get(AbstractNpcOnlyEntity.ACTION_TARGET_POS).y)
        nbt.putInt("eyeColorZ", data.get(AbstractNpcOnlyEntity.ACTION_TARGET_POS).z)
        nbt.putString("model", data.get(AbstractNpcOnlyEntity.APPEARANCE_DNA))
        nbt.putString("girlID", data.get(BaseGirlEntity.GIRL_ID))
        nbt.putString("queen", data.get(GoblinEntity.QUEEN_ID))
        nbt.putBoolean("isQueen", goblin.isQueen)
        nbt.putBoolean("isTamed", data.get(GoblinEntity.TAMED))
        nbt.putInt("robTicks", goblin.robTicks)
        if (goblin.isQueen) {
            nbt.putBoolean("preggo", data.get(GoblinEntity.PREGNANT))
            nbt.putFloat("throneRot", goblin.throneYaw)
            nbt.putDouble("thronePosX", goblin.thronePos.x)
            nbt.putDouble("thronePosY", goblin.thronePos.y)
            nbt.putDouble("thronePosZ", goblin.thronePos.z)
            nbt.putLong("impregnationTick", goblin.impregnationTick)

            for (i in goblin.guardUUIDs.indices) {
                nbt.putString("guard$i", goblin.guardUUIDs[i].toString())
            }
        }
    }

    @JvmStatic
    fun read(goblin: GoblinEntity, nbt: CompoundTag) {
        val data = goblin.getEntityData()
        goblin.isQueen = nbt.getBoolean("isQueen")
        // 1.21 /summon loads an empty tag into a fresh entity before finalizeSpawn; keep the
        // constructor defaults instead of parsing a missing model code.
        if (!nbt.contains("model")) return
        data.set(AbstractNpcOnlyEntity.APPEARANCE_DNA, nbt.getString("model"))
        data.set(AbstractNpcOnlyEntity.CURRENT_ACTION, nbt.getString("bodyColor"))
        val parts = AbstractNpcOnlyEntity.getModelCodeParts(goblin)
        if (parts.size > 4 && ((parts[3].toIntOrNull() ?: 0) > 7 || (parts[4].toIntOrNull() ?: 0) > 7)) {
            data.set(AbstractNpcOnlyEntity.APPEARANCE_DNA, goblin.buildModelCodeDNA(StringBuilder(), goblin.getModelPartIndex()))
            Main.LOGGER.info("updated an old Goblin")
        }

        data.set(AbstractNpcOnlyEntity.ACTION_TARGET_POS, BlockPos(nbt.getInt("eyeColorX"), nbt.getInt("eyeColorY"), nbt.getInt("eyeColorZ")))
        data.set(BaseGirlEntity.GIRL_ID, nbt.getString("girlID"))
        data.set(GoblinEntity.QUEEN_ID, nbt.getString("queen"))
        data.set(GoblinEntity.TAMED, nbt.getBoolean("isTamed"))
        goblin.robTicks = nbt.getInt("robTicks")
        if (goblin.isQueen) {
            goblin.throneYaw = nbt.getFloat("throneRot")
            goblin.thronePos = Vec3(nbt.getDouble("thronePosX"), nbt.getDouble("thronePosY"), nbt.getDouble("thronePosZ"))

            var i = 0
            while ("" != nbt.getString("guard$i")) {
                goblin.guardUUIDs.add(UUID.fromString(nbt.getString("guard$i")))
                i++
            }

            data.set(GoblinEntity.PREGNANT, nbt.getBoolean("preggo"))
            goblin.impregnationTick = nbt.getLong("impregnationTick")
        }
    }
}
