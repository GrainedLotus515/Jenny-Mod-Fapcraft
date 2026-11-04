package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.NpcType
import java.util.UUID
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.level.LevelEvent

/**
 * WorldSavedData for Allie companions (tamed Allie state per world).
 *
 * Despite the name it persists the per-player custom girl names
 * (`npcNamesByPlayer`: player UUID -> NpcType -> model name) under the 1.12
 * id `sexmod:customstaticgirlnames` (1.21 file `sexmod_customstaticgirlnames`,
 * PORTING.md §2.11). The map is static; the instance only (de)serializes it.
 */
open class AllieWorldData : SavedData {
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
        for (key in nbt.allKeys) {
            val uuid: UUID
            try {
                uuid = UUID.fromString(key)
            } catch (exception: IllegalArgumentException) {
                continue
            }

            npcNamesByPlayer[uuid] = this.deserializeNpcTypes(nbt.getCompound(key))
        }
    }

    open fun writeToNBT(nbt: CompoundTag): CompoundTag {
        for (entry in npcNamesByPlayer.entries) {
            val uuid = entry.key
            nbt.put(uuid.toString(), this.serializeNpcTypes(entry.value))
        }

        return nbt
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = this.writeToNBT(tag)

    private fun serializeNpcTypes(typeMap: HashMap<NpcType, String>): CompoundTag {
        val tag = CompoundTag()

        for (entry in typeMap.entries) {
            tag.putString(entry.key.name, entry.value)
        }

        return tag
    }

    private fun deserializeNpcTypes(tag: CompoundTag): HashMap<NpcType, String> {
        val typeMap = HashMap<NpcType, String>()

        for (type in NpcType.values()) {
            val modelName = tag.getString(type.name)
            if ("" != modelName) {
                typeMap[type] = modelName
            }
        }

        return typeMap
    }

    companion object {
        /** 1.21 data file name (1.12 `sexmod:customstaticgirlnames`; `:` is not allowed in file names). */
        const val DATA_NAME: String = "sexmod_customstaticgirlnames"

        @JvmField
        val npcNamesByPlayer: HashMap<UUID, HashMap<NpcType, String>> = HashMap()

        @JvmStatic
        fun load(tag: CompoundTag, registries: HolderLookup.Provider): AllieWorldData {
            val data = AllieWorldData()
            data.readFromNBT(tag)
            return data
        }

        @JvmStatic
        fun factory(): SavedData.Factory<AllieWorldData> = SavedData.Factory({ AllieWorldData() }, ::load, null)

        @JvmStatic
        fun addAllie(playerUuid: UUID, type: NpcType, modelName: String) {
            var typeMap = npcNamesByPlayer[playerUuid]
            if (typeMap == null) {
                typeMap = HashMap()
            }

            typeMap[type] = modelName
            npcNamesByPlayer[playerUuid] = typeMap
        }

        @JvmStatic
        fun getNpcName(playerUuid: UUID, type: NpcType): String? {
            val typeMap = npcNamesByPlayer[playerUuid]
            return typeMap?.get(type)
        }
    }
}
