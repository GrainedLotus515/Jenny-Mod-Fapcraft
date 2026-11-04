package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.util.KoboldManager
import java.util.Optional
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag

/**
 * NBT persistence for [KoboldEntity]: body size, eye colour, appearance
 * DNA, name, master, inventory, body colour and the tribe block (tribe id,
 * leader flag, tribe name).
 *
 * <b>Pitfalls.</b> The key names are part of the save format — renaming any of
 * them silently wipes that field on existing worlds. [read] keeps the
 * tribe lookup inside a `!isDead` guard so a despawned kobold re-inserted
 * by chunk load cannot resurrect a tribe entry for a corpse.
 *
 * 1.21: `setUniqueId`/`getUniqueId` are `putUUID`/`getUUID`. The 1.12
 * `getUniqueId` never returned null (a missing key read as UUID(0, 0)); that
 * value is kept for a missing key so the behaviour is unchanged.
 */
object KoboldNbtIO {
    @JvmStatic
    fun write(entity: KoboldEntity, nbt: CompoundTag) {
        val data = entity.getEntityData()
        nbt.putFloat("body_size", data.get(KoboldEntity.SIZE_SCALAR))
        nbt.putInt("eyeColorX", data.get(KoboldEntity.ACTION_TARGET_POS).x)
        nbt.putInt("eyeColorY", data.get(KoboldEntity.ACTION_TARGET_POS).y)
        nbt.putInt("eyeColorZ", data.get(KoboldEntity.ACTION_TARGET_POS).z)
        nbt.putString("model", data.get(KoboldEntity.APPEARANCE_DNA))
        nbt.putString("name", data.get(KoboldEntity.KOBOLD_NAME))
        nbt.putString("master", data.get(BaseGirlEntity.MASTER))
        nbt.put("inventory", entity.inventory.serializeNBT(entity.registryAccess()))
        nbt.putString("bodyColor", data.get(KoboldEntity.CURRENT_ACTION))
        nbt.putBoolean("editedColorManually", entity.editedColorManually)
        val tribeOpt = data.get(KoboldEntity.TRIBE_ID)
        if (tribeOpt.isPresent) {
            nbt.putUUID("tribeId", tribeOpt.get())
            nbt.putBoolean("isLeader", KoboldManager.isTribeMember(tribeOpt.get(), entity))
            nbt.putString("tribeName", data.get(KoboldEntity.TRIBE_NAME))
        }
    }

    @JvmStatic
    fun read(entity: KoboldEntity, nbt: CompoundTag) {
        val data = entity.getEntityData()
        val modelCode = nbt.getString("model")
        if ("" != modelCode) {
            data.set(KoboldEntity.APPEARANCE_DNA, modelCode)
        }

        val eyeColor = BlockPos(nbt.getInt("eyeColorX"), nbt.getInt("eyeColorY"), nbt.getInt("eyeColorZ"))
        if (BlockPos.ZERO != eyeColor) {
            data.set(KoboldEntity.ACTION_TARGET_POS, eyeColor)
        }

        data.set(KoboldEntity.SIZE_SCALAR, nbt.getFloat("body_size"))
        data.set(KoboldEntity.KOBOLD_NAME, nbt.getString("name"))
        data.set(BaseGirlEntity.MASTER, nbt.getString("master"))
        entity.inventory.deserializeNBT(entity.registryAccess(), nbt.getCompound("inventory"))
        val bodyColor = nbt.getString("bodyColor")
        if ("" != bodyColor) {
            data.set(KoboldEntity.CURRENT_ACTION, nbt.getString("bodyColor"))
        }

        entity.editedColorManually = nbt.getBoolean("editedColorManually")
        // 1.12 getUniqueId: a missing key read as UUID(0, 0), never null
        val tribeId: UUID? = if (nbt.hasUUID("tribeId")) nbt.getUUID("tribeId") else UUID(0L, 0L)
        if (tribeId != null && !entity.isRemoved) {
            data.set(KoboldEntity.TRIBE_ID, Optional.of(tribeId))
            if (!KoboldManager.doesTribeExist(tribeId)) {
                KoboldManager.setTribeColor(tribeId, EyeAndKoboldColor.valueOf(data.get(KoboldEntity.CURRENT_ACTION)))
            }

            KoboldManager.addTribeMember(tribeId, entity)
            if (nbt.getBoolean("isLeader")) {
                KoboldManager.isTribeMember(tribeId, entity)
            }

            data.set(KoboldEntity.TRIBE_NAME, nbt.getString("tribeName"))
        }
    }
}
