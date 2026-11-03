package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GalathEntity
import com.trolmastercard.sexmod.entity.ManglelieEntity
import java.util.UUID
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.level.LevelEvent

/**
 * <b>Role.</b> Persistence of custom model codes for Galath and Manglelie —
 * a [SavedData] (1.12 "sexmod:static_custom_model_manager", file
 * `sexmod_static_custom_model_manager`) storing UUID -> model-code maps (one
 * for galaths, one for manglelies) so a girl's custom outfit survives world
 * reloads. Lookups fall back to the girl's own UUID when she has no owner.
 *
 * <b>Who uses it.</b> [BaseGirlEntity]/[GalathEntity] model-code
 * resolution and the clothing editor. Keep the two maps ([galathModelCodes] = galath,
 * [manglelieModelCodes] = manglelie) distinct — mixing them garbles outfits on reload.
 *
 * Port note: same static-state / I/O-shell pattern as [GirlSavedData].
 */
open class GirlWorldData : SavedData {
    constructor() : super()

    constructor(dataId: String) : super()

    @SubscribeEvent
    open fun onSave(event: LevelEvent.Save) {
        val world = event.level as? ServerLevel ?: return
        world.server.overworld().dataStorage.set(DATA_NAME, this)
        this.setDirty()
    }

    @SubscribeEvent
    open fun onLoad(event: LevelEvent.Load) {
        val world = event.level as? ServerLevel ?: return
        world.server.overworld().dataStorage.computeIfAbsent(factory(), DATA_NAME)
    }

    open fun readFromNBT(nbt: CompoundTag) {
        val tag = nbt.getCompound("sexmod:static_custom_model_manager")
        this.writeNBT(tag.getCompound("galath"), galathModelCodes)
        this.writeNBT(tag.getCompound("mang"), manglelieModelCodes)
    }

    open fun writeToNBT(nbt: CompoundTag): CompoundTag {
        val tag = CompoundTag()
        tag.put("galath", this.serializeOwnership(galathModelCodes))
        tag.put("mang", this.serializeOwnership(manglelieModelCodes))
        nbt.put("sexmod:static_custom_model_manager", tag)
        return nbt
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = this.writeToNBT(tag)

    open fun serializeOwnership(ownershipMap: HashMap<UUID, String>): CompoundTag {
        val tag = CompoundTag()
        var i = 0

        for (entry in ownershipMap.entries) {
            val uuid = entry.key
            tag.putString("UUID$i", uuid.toString())
            tag.putString("MODEL$i", entry.value)
            i++
        }

        return tag
    }

    open fun writeNBT(tag: CompoundTag, ownershipMap: HashMap<UUID, String>) {
        var i = 0

        while (true) {
            val uuidString = tag.getString("UUID$i")
            if ("" == uuidString) {
                return
            }

            ownershipMap[UUID.fromString(uuidString)] = tag.getString("MODEL$i")
            i++
        }
    }

    companion object {
        /** 1.21 data file name (`:` is not allowed in file names, PORTING.md §2.11). */
        const val DATA_NAME: String = "sexmod_static_custom_model_manager"

        @JvmField
        var galathModelCodes: HashMap<UUID, String> = HashMap()

        @JvmField
        var manglelieModelCodes: HashMap<UUID, String> = HashMap()

        @JvmStatic
        fun load(tag: CompoundTag, registries: HolderLookup.Provider): GirlWorldData {
            val data = GirlWorldData()
            data.readFromNBT(tag)
            return data
        }

        @JvmStatic
        fun factory(): SavedData.Factory<GirlWorldData> = SavedData.Factory({ GirlWorldData() }, ::load, null)

        @JvmStatic
        fun getCustomModelCode(girl: BaseGirlEntity): String {
            val code = buildModelCode(girl)
            return code ?: ""
        }

        private fun buildModelCode(girl: BaseGirlEntity): String? {
            if (girl is GalathEntity) {
                val girlId = girl.getGirlId()
                var ownerId = GirlSavedData.getManglelieOwnerId(girlId)
                if (ownerId == null) {
                    ownerId = girlId
                }

                return galathModelCodes[ownerId]
            } else if (girl is ManglelieEntity) {
                val ownerId2 = GirlSavedData.getManglelieOwnerId(girl.getCorruptPlayerUUID())
                return manglelieModelCodes[ownerId2 ?: girl.getGirlId()]
            } else {
                return null
            }
        }

        @JvmStatic
        fun setCustomModelCode(girl: BaseGirlEntity) {
            if (girl is GalathEntity) {
                val girlId = girl.getGirlId()
                var ownerId = GirlSavedData.getManglelieOwnerId(girlId)
                if (ownerId == null) {
                    ownerId = girlId
                }

                galathModelCodes[ownerId] = girl.getCustomModelCode()
            } else {
                if (girl is ManglelieEntity) {
                    val ownerId2 = GirlSavedData.getManglelieOwnerId(girl.getCorruptPlayerUUID())
                    manglelieModelCodes[ownerId2 ?: girl.getGirlId()] = girl.getCustomModelCode()
                }
            }
        }

        @JvmStatic
        fun clearAll() {
            galathModelCodes.clear()
            manglelieModelCodes.clear()
        }
    }
}
