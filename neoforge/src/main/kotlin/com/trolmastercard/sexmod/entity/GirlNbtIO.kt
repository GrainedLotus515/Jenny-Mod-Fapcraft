package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import java.util.UUID
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.Vec3
import org.apache.logging.log4j.Level

/**
 * NBT (de)serialisation for the state [BaseGirlEntity] owns itself:
 * home position, persistent girl id, custom name and custom-model code.
 *
 * Extracted verbatim from the entity's `addAdditionalSaveData` /
 * `readAdditionalSaveData` overrides (1.12 `writeEntityToNBT` /
 * `readEntityFromNBT`). The vanilla `super` calls stay in the entity so read
 * order (super first) and write order (fields then super) are unchanged.
 */
object GirlNbtIO {
    @JvmStatic
    fun write(girl: BaseGirlEntity, nbt: CompoundTag) {
        nbt.putDouble("homeX", girl.homePos.x)
        nbt.putDouble("homeY", girl.homePos.y)
        nbt.putDouble("homeZ", girl.homePos.z)
        nbt.putString("girlID", girl.entityDataManager.get(BaseGirlEntity.GIRL_ID))
        val customName = girl.getGirlCustomName()
        if ("" != customName) {
            nbt.putString("sexmod:customname", customName)
        }

        if (girl.supportsCustomModels()) {
            nbt.putString("sexmod:customModel", girl.getCustomModelCode())
        }
    }

    @JvmStatic
    fun read(girl: BaseGirlEntity, nbt: CompoundTag) {
        girl.homePos = Vec3(nbt.getDouble("homeX"), nbt.getDouble("homeY"), nbt.getDouble("homeZ"))
        val customName = nbt.getString("sexmod:customname")
        if ("" != customName) {
            girl.setCustomNameOverride(customName)
        }

        val girlId = nbt.getString("girlID")
        if ("" != girlId) {
            val uuid = UUID.fromString(girlId)
            var duped = false

            for (other in BaseGirlEntity.girlList(uuid)) {
                if (!other.level().isClientSide && other !== girl && !other.isRemoved && other.isAddedToLevel) {
                    duped = true
                    break
                }
            }

            if (duped) {
                Main.LOGGER.log(Level.WARN, String.format("got a duped %s with id '%s'. Deleted her", girl.getDisplayNameText(), uuid))
                girl.discard()
            } else {
                girl.entityDataManager.set(BaseGirlEntity.GIRL_ID, uuid.toString())
                if (girl.supportsCustomModels()) {
                    girl.setCustomModelCode(nbt.getString("sexmod:customModel"))
                }
            }
        }
    }
}
