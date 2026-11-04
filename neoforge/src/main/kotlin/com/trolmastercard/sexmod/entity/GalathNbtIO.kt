package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.util.AllieWorldData
import net.minecraft.nbt.CompoundTag

/**
 * <b>Role.</b> Galath's extra NBT keys, split out of [GalathEntity]:
 * the bound master UUID (`sexmod:master`, mirrored into the
 * `MASTER` data key) and the despawn marker (`sexmod:despawned`).
 * The vanilla/BaseGirlEntity keys stay in the entity's own overrides, which
 * call `super` and then [write] / [read].
 *
 * <b>Pitfalls.</b> Reading must restore the custom NPC name after the master
 * key, otherwise a renamed Galath loses her name on load.
 */
object GalathNbtIO {
    @JvmStatic
    fun write(galath: GalathEntity, nbt: CompoundTag) {
        nbt.putString("sexmod:master", galath.entityData.get(BaseGirlEntity.MASTER))
        if (galath.markedDespawned) {
            nbt.putBoolean("sexmod:despawned", true)
        }
    }

    @JvmStatic
    fun read(galath: GalathEntity, nbt: CompoundTag) {
        galath.entityData.set(BaseGirlEntity.MASTER, nbt.getString("sexmod:master"))
        if (nbt.getBoolean("sexmod:despawned")) {
            galath.despawned = true
        }

        val masterUuid = galath.getMasterUUID()
        if (masterUuid != null) {
            val npcName = AllieWorldData.getNpcName(masterUuid, NpcType.GALATH)
            if (npcName != null) {
                galath.setCustomNameOverride(npcName)
            }
        }
    }
}
