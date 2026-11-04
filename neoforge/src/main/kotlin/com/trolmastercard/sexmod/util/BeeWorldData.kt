package com.trolmastercard.sexmod.util

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.saveddata.SavedData
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.level.LevelEvent

/**
 * WorldSavedData for bee companions (tamed bee state per world).
 *
 * Despite the name it stores the Galath spawn bookkeeping (1.12
 * "sexmod:galath_spawn_list", 1.21 file `sexmod_galath_spawn_list`):
 * [hivePositions] (positions where a wild Galath replaced a wither
 * skeleton/blaze spawn) and [flowerPositions] (key suffix "mang").
 * The lists are static; the instance is only the I/O shell.
 */
open class BeeWorldData : SavedData {
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
        val tag = nbt.getCompound("sexmod:galath_spawn_list")
        this.readNBT(tag, "", hivePositions)
        this.readNBT(tag, "mang", flowerPositions)
    }

    open fun writeToNBT(nbt: CompoundTag): CompoundTag {
        val tag = CompoundTag()
        this.writeNBT(tag, "", hivePositions)
        this.writeNBT(tag, "mang", flowerPositions)
        nbt.put("sexmod:galath_spawn_list", tag)
        return nbt
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = this.writeToNBT(tag)

    open fun writeNBT(nbt: CompoundTag, key: String, positions: List<BlockPos>) {
        nbt.putInt("sexmod:pos_amount$key", positions.size)
        var i = 0

        for (pos in positions) {
            nbt.putInt("sexmod:x$key$i", pos.x)
            nbt.putInt("sexmod:y$key$i", pos.y)
            nbt.putInt("sexmod:z$key$i", pos.z)
            i++
        }
    }

    open fun readNBT(nbt: CompoundTag, key: String, positions: MutableList<BlockPos>) {
        positions.clear()
        val count = nbt.getInt("sexmod:pos_amount$key")

        for (i in 0 until count) {
            positions.add(
                BlockPos(nbt.getInt("sexmod:x$key$i"), nbt.getInt("sexmod:y$key$i"), nbt.getInt("sexmod:z$key$i"))
            )
        }
    }

    companion object {
        /** 1.21 data file name (`:` is not allowed in file names, PORTING.md §2.11). */
        const val DATA_NAME: String = "sexmod_galath_spawn_list"

        @JvmField
        val hivePositions: MutableList<BlockPos> = ArrayList()

        @JvmField
        val flowerPositions: MutableList<BlockPos> = ArrayList()

        @JvmStatic
        fun addHivePosition(hivePos: BlockPos, positions: MutableList<BlockPos>) {
            positions.add(hivePos)
        }

        @JvmStatic
        fun load(tag: CompoundTag, registries: HolderLookup.Provider): BeeWorldData {
            val data = BeeWorldData()
            data.readFromNBT(tag)
            return data
        }

        @JvmStatic
        fun factory(): SavedData.Factory<BeeWorldData> = SavedData.Factory({ BeeWorldData() }, ::load, null)
    }
}
