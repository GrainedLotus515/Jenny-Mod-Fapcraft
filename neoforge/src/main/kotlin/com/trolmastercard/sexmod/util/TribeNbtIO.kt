package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.EyeAndKoboldColor
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.nbt.CompoundTag

/**
 * <b>Role.</b> The "tribes" world-data layout for [KoboldManager]: turns
 * every tribe (color, master, member/saved positions, beds, chests and tasks
 * with their mining targets) into one string-indexed NBT compound and reads it
 * back on load.
 *
 * <b>Pitfalls.</b> Read and write must stay in lockstep — the keys are
 * `tribeId<i>`, `<tribeId>member<j>pos|id`, `<tribeId>bed<j>`,
 * `<tribeId>chest<j>` and `<tribeId><j>taskKind|facing|pos|block<k>`.
 * [readNBTValue] *empties* each key as it reads, so a compound cannot be
 * read twice; an empty id/kind/position string is the end marker of each loop.
 */
object TribeNbtIO {
    @JvmStatic
    fun readNBTValue(key: String, nbt: CompoundTag): String {
        val value = nbt.getString(key)
        nbt.putString(key, "")
        return value
    }

    private fun parsePos(str: String): BlockPos {
        val parts = str.split("|")
        return BlockPos(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    }

    @JvmStatic
    fun read(nbt: CompoundTag) {
        var i = 0

        while (true) {
            val tribeIdStr = readNBTValue("tribeId$i", nbt)
            if ("" == tribeIdStr) {
                return
            }

            val tribeUuid = UUID.fromString(tribeIdStr)
            val color = EyeAndKoboldColor.valueOf(readNBTValue("tribeColor$i", nbt))
            KoboldManager.setTribeColor(tribeUuid, color)
            val masterStr = readNBTValue("tribeMaster$i", nbt)
            if ("" != masterStr) {
                KoboldManager.assignMaster(tribeUuid, UUID.fromString(masterStr))
            }

            var i2 = 0

            while (true) {
                val memberPosStr = readNBTValue(tribeUuid.toString() + "member" + i2 + "pos", nbt)
                if ("" == memberPosStr) {
                    break
                }

                val memberIdStr = readNBTValue(tribeUuid.toString() + "member" + i2 + "id", nbt)
                if ("" == memberIdStr) {
                    break
                }

                val pos = parsePos(memberPosStr)
                val memberUuid = UUID.fromString(memberIdStr)
                KoboldManager.setTribeHomeForMember(tribeUuid, memberUuid, pos)
                i2++
            }

            var i3 = 0

            while (true) {
                val bedStr = readNBTValue(tribeUuid.toString() + "bed" + i3, nbt)
                if ("" == bedStr) {
                    break
                }

                KoboldManager.addTribeBed(tribeUuid, parsePos(bedStr))
                i3++
            }

            var i4 = 0

            while (true) {
                val chestStr = readNBTValue(tribeUuid.toString() + "chest" + i4, nbt)
                if ("" == chestStr) {
                    break
                }

                KoboldManager.addTribeChest(tribeUuid, parsePos(chestStr))
                i4++
            }

            var i5 = 0

            while (true) {
                val taskKind = readNBTValue(tribeUuid.toString() + i5 + "taskKind", nbt)
                if ("" == taskKind) {
                    break
                }

                val facingStr = readNBTValue(tribeUuid.toString() + i5 + "facing", nbt)
                var facing = Direction.NORTH
                if ("" != facingStr) {
                    // 1.12 EnumFacing.byName returned null for unknown names; keep NORTH instead of crashing later
                    facing = Direction.byName(facingStr) ?: Direction.NORTH
                }

                val taskPos = parsePos(readNBTValue(tribeUuid.toString() + i5 + "pos", nbt))
                val targetBlocks = HashSet<BlockPos>()
                var i6 = 0

                while (true) {
                    val blockStr = readNBTValue(tribeUuid.toString() + i5 + "block" + i6, nbt)
                    if ("" == blockStr) {
                        KoboldManager.addTask(tribeUuid, KoboldTask(taskPos, KoboldTask.TaskType.valueOf(taskKind), targetBlocks, facing))
                        i5++
                        break
                    }

                    targetBlocks.add(parsePos(blockStr))
                    i6++
                }
            }

            i++
        }
    }

    @JvmStatic
    fun write(nbt: CompoundTag, tribes: Map<UUID, KoboldManager.Tribe>): CompoundTag {
        var i = 0

        for ((tribeUuid, tribe) in tribes) {
            val masterUuid = tribe.getMasterPlayerUUID()
            nbt.putString("tribeId$i", tribeUuid.toString())
            nbt.putString("tribeColor$i", tribe.tribeColor.toString())
            if (masterUuid != null) {
                nbt.putString("tribeMaster$i", masterUuid.toString())
            }

            var i2 = 0
            val seenMemberIds = HashSet<UUID>()

            for (member in tribe.members) {
                if (!member.isRemoved) {
                    val memberPos = member.blockPosition()
                    val memberUuid = member.getGirlId()
                    nbt.putString(
                        tribeUuid.toString() + "member" + i2 + "pos", memberPos.x.toString() + "|" + memberPos.y + "|" + memberPos.z,
                    )
                    nbt.putString(tribeUuid.toString() + "member" + i2 + "id", memberUuid.toString())
                    seenMemberIds.add(memberUuid)
                    i2++
                }
            }

            for ((memberUuid2, savedPos) in tribe.savedPositions) {
                if (!seenMemberIds.contains(memberUuid2)) {
                    nbt.putString(
                        tribeUuid.toString() + "member" + i2 + "pos", savedPos.x.toString() + "|" + savedPos.y + "|" + savedPos.z,
                    )
                    nbt.putString(tribeUuid.toString() + "member" + i2 + "id", memberUuid2.toString())
                    seenMemberIds.add(memberUuid2)
                    i2++
                }
            }

            var i3 = 0

            // jar-verified: the deobf wrote chests under "bed" keys and beds under
            // "chest" keys (crossed); the original writes the addTribeBed set as
            // "bed" and the addTribeChest set as "chest"
            for (bedPos in tribe.tribeBeds) {
                nbt.putString(tribeUuid.toString() + "bed" + i3, bedPos.x.toString() + "|" + bedPos.y + "|" + bedPos.z)
                i3++
            }

            var i4 = 0

            for (chestPos in tribe.tribeChests) {
                nbt.putString(tribeUuid.toString() + "chest" + i4, chestPos.x.toString() + "|" + chestPos.y + "|" + chestPos.z)
                i4++
            }

            var i5 = 0

            for (task in tribe.tasks) {
                nbt.putString(tribeUuid.toString() + i5 + "taskKind", task.taskType.toString())
                nbt.putString(tribeUuid.toString() + i5 + "pos", task.targetPos.x.toString() + "|" + task.targetPos.y + "|" + task.targetPos.z)
                nbt.putString(tribeUuid.toString() + i5 + "facing", task.facing.getName())
                var i6 = 0

                for (targetPos in task.miningTargets) {
                    nbt.putString(
                        tribeUuid.toString() + i5 + "block" + i6, targetPos.x.toString() + "|" + targetPos.y + "|" + targetPos.z,
                    )
                    i6++
                }

                i5++
            }

            i++
        }

        return nbt
    }
}
