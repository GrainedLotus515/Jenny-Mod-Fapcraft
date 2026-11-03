package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.GalathEntity
import com.trolmastercard.sexmod.networking.InformOfOwnershipPacket
import com.trolmastercard.sexmod.networking.PacketHandler
import java.util.UUID
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.apache.logging.log4j.Level as LogLevel

/**
 * <b>Role.</b> Galath/Manglelie ownership persistence — a
 * [SavedData] (1.12 "sexmod:galath_owner_ship", file `sexmod_galath_owner_ship`)
 * storing player<->galath pairs, per-owner last-cum-dosage time and the set of
 * manglelie-owned players. This is the *ownership* system, NOT the scene system
 * (see `KoboldManager` and the networking scene packets for that).
 *
 * <b>State.</b> `ownerToGirl` = bidirectional player UUID <-> girl UUID map,
 * `lastCumTimeByPlayer` = last cum-time per player (drives despawn/cooldown),
 * `mangOwnershipSet` = players who own a manglelie. Static accessors are used by
 * `GalathCoinItem`, `RequestRidingPacket` and the galath AI.
 *
 * <b>Server tick.</b> Ownership is dropped (and the client informed via
 * [InformOfOwnershipPacket]) when the owner is online but his galath is
 * gone — no stale pairs survive a missing girl.
 *
 * <b>Pitfall.</b> [debugEnabled] is a *client-side* mirror of "am I
 * owned" (see [InformOfOwnershipPacket]); do not use it for server logic.
 *
 * Port notes: like 1.12, the persisted state is static and the instances are
 * only I/O shells: [onLoad] loads the file into the statics through
 * `computeIfAbsent`, [onSave] stores this handler instance and marks it dirty
 * so [save] writes the statics. 1.12 `getUniqueId`/`setUniqueId` (two longs)
 * are 1.21 `getUUID`/`putUUID` (int array); old worlds are out of scope.
 */
open class GirlSavedData : SavedData {
    constructor() : super()

    constructor(dataId: String) : super()

    @SubscribeEvent
    open fun onServerTick(event: ServerTickEvent.Post) {
        val world: Level = event.server.overworld()
        val playersToNotify = ArrayList<Player>()

        for (entry in ownerToGirl.entrySet()) {
            val ownerUuid: UUID = entry.key
            val girlUuid: UUID = entry.value
            val owner = world.getPlayerByUUID(ownerUuid)
            if (owner != null && BaseGirlEntity.getServerGirlEntity(girlUuid) == null) {
                playersToNotify.add(owner)
            }
        }

        for (player in playersToNotify) {
            ownerToGirl.removeByKey(player.uuid)
            PacketHandler.networkWrapper.sendTo(InformOfOwnershipPacket(false), player as ServerPlayer)
        }
    }

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
        val tag = nbt.getCompound("sexmod:ownershipdata")
        val count = tag.getInt("amount")

        for (i in 0 until count) {
            if (tag.hasUUID("master$i") && tag.hasUUID("galath$i")) {
                val masterUuid = tag.getUUID("master$i")
                val galathUuid = tag.getUUID("galath$i")
                val lastCumTime = tag.getLong("lastcumdosage$i")
                ownerToGirl.put(masterUuid, galathUuid)
                lastCumTimeByPlayer[masterUuid] = lastCumTime
            } else {
                Main.LOGGER.fatal("OMFG WHOOP WHOOP SAVING DIDNT WORK CORRECTLY AAAAAAAAAAA")
            }
        }

        val mangTag = nbt.getCompound("sexmod:mangownershipdata")

        var i2 = 0
        while (mangTag.hasUUID("mang$i2")) {
            mangOwnershipSet.add(mangTag.getUUID("mang$i2"))
            i2++
        }

        nbt.put("sexmod:mangownershipdata", CompoundTag())
        nbt.put("sexmod:ownershipdata", CompoundTag())
    }

    open fun writeToNBT(nbt: CompoundTag): CompoundTag {
        val tag = CompoundTag()
        tag.putInt("amount", ownerToGirl.size())
        var i = 0

        for (entry in ownerToGirl.entrySet()) {
            val masterUuid: UUID = entry.key
            val galathUuid: UUID = entry.value
            val cumTime: Long = lastCumTimeByPlayer[masterUuid] ?: 0L

            tag.putUUID("galath$i", galathUuid)
            tag.putUUID("master$i", masterUuid)
            tag.putLong("lastcumdosage$i", cumTime)
            i++
        }

        val mangTag = CompoundTag()
        i = 0

        for (uuid in mangOwnershipSet) {
            mangTag.putUUID("mang" + i++, uuid)
        }

        nbt.put("sexmod:ownershipdata", tag)
        nbt.put("sexmod:mangownershipdata", mangTag)
        return nbt
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = this.writeToNBT(tag)

    companion object {
        @JvmField
        var debugEnabled: Boolean = true

        const val CUM_TIMEOUT: Float = 60.0f
        const val GALATH_OWNERSHIP_KEY: String = "sexmod:galath_owner_ship"
        const val OWNERSHIP_DATA_KEY: String = "sexmod:ownershipdata"
        const val MANG_OWNERSHIP_DATA_KEY: String = "sexmod:mangownershipdata"

        /** 1.21 data file name (`:` is not allowed in file names, PORTING.md §2.11). */
        const val DATA_NAME: String = "sexmod_galath_owner_ship"

        @JvmField
        var ownerToGirl: BiDirectionalMap<UUID, UUID> = BiDirectionalMap()

        @JvmField
        var lastCumTimeByPlayer: HashMap<UUID, Long> = HashMap()

        @JvmField
        var mangOwnershipSet: HashSet<UUID> = HashSet()

        @JvmStatic
        fun load(tag: CompoundTag, registries: HolderLookup.Provider): GirlSavedData {
            val data = GirlSavedData()
            data.readFromNBT(tag)
            return data
        }

        @JvmStatic
        fun factory(): SavedData.Factory<GirlSavedData> = SavedData.Factory({ GirlSavedData() }, ::load, null)

        @JvmStatic
        fun clearAll() {
            mangOwnershipSet.clear()
            ownerToGirl.clear()
        }

        @JvmStatic
        fun markAsManglelieOwned(girlUuid: UUID?) {
            val ownerId = getManglelieOwnerId(girlUuid)
            if (ownerId != null) {
                mangOwnershipSet.add(ownerId)
            }
        }

        @JvmStatic
        fun isManglelieOwned(girlUuid: UUID?): Boolean = girlUuid != null && mangOwnershipSet.contains(girlUuid)

        @JvmStatic
        fun isOwnerNearby(galath: GalathEntity): Boolean {
            val ownerId = ownerToGirl.getByValue(galath.getGirlId())
            if (ownerId == null) {
                return false
            } else {
                val world = galath.level()
                val owner = world.getPlayerByUUID(ownerId)
                if (owner == null) {
                    return true
                } else {
                    return if (owner.level().dimension() != galath.level().dimension()) false else !(owner.distanceTo(galath) > 60.0f)
                }
            }
        }

        @JvmStatic
        fun isOwnerOf(player: Player, galath: GalathEntity): Boolean {
            return galath.getGirlId() == ownerToGirl.getByKey(player.uuid)
        }

        @JvmStatic
        fun updateMangleliePartner(galath: GalathEntity) {
            val partner = galath.getMangleliePartner(true)
            partner?.discard()

            val ownerId = ownerToGirl.getByValue(galath.getGirlId())
            if (ownerId == null) {
                galath.discard()
            } else {
                val world = galath.level()
                val owner = world.getPlayerByUUID(ownerId)
                galath.discard()
                ownerToGirl.removeByKey(ownerId)
                if (owner != null) {
                    PacketHandler.networkWrapper.sendTo(InformOfOwnershipPacket(false), owner as ServerPlayer)
                }
            }
        }

        @JvmStatic
        fun hasOwner(uuid: UUID?): Boolean = uuid != null && ownerToGirl.getByKey(uuid) != null

        @JvmStatic
        fun getManglelieOwnerId(uuid: UUID?): UUID? = if (uuid == null) null else ownerToGirl.getByValue(uuid)

        @JvmStatic
        fun getManglelieOwnerOf(galath: GalathEntity?): UUID? {
            return if (galath == null) null else getManglelieOwnerId(galath.getGirlId())
        }

        @JvmStatic
        fun getOwnerId(uuid: UUID?): UUID? = if (uuid == null) null else ownerToGirl.getByKey(uuid)

        @JvmStatic
        fun getOwnerOf(player: Player?): UUID? {
            return if (player == null) null else getOwnerId(player.uuid)
        }

        @JvmStatic
        fun setOwnerShip(playerUuid: UUID, girlUuid: UUID) {
            ownerToGirl.put(playerUuid, girlUuid)
        }

        @JvmStatic
        fun grantOwnership(player: Player?, galath: GalathEntity?) {
            if (player != null) {
                if (galath != null) {
                    setOwnerShip(player.uuid, galath.getGirlId())
                }
            }
        }

        @JvmStatic
        fun removeOwner(uuid: UUID?) {
            if (uuid != null) {
                ownerToGirl.removeByKey(uuid)
            }
        }

        @JvmStatic
        fun removeOwnerOf(player: Player?) {
            if (player != null) {
                removeOwner(player.uuid)
            }
        }

        @JvmStatic
        fun shouldDespawn(girlUuid: UUID?, world: Level): Boolean {
            val lastCumTime = if (girlUuid == null) null else lastCumTimeByPlayer[girlUuid]
            if (!isManglelieOwned(girlUuid)) {
                return false
            } else {
                // jar-intent: only despawn within the CUM_TIMEOUT window; the original
                // compared against 0L, making the 60s constant dead and the check
                // always-true after any cum
                return if (lastCumTime == null) true else (world.gameTime - lastCumTime).toFloat() > CUM_TIMEOUT
            }
        }

        @JvmStatic
        fun saveCumTime(playerUuid: UUID?, time: Long) {
            if (playerUuid == null) {
                Main.LOGGER.log(LogLevel.WARN, "tried to save last cum dosage time on NULL player")
            } else {
                lastCumTimeByPlayer[playerUuid] = time
            }
        }
    }
}
