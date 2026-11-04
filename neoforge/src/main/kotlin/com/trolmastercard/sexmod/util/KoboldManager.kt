package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.EyeAndKoboldColor
import com.trolmastercard.sexmod.entity.KoboldEntity
import com.trolmastercard.sexmod.entity.ai.NearestAttackableGirlGoal
import com.trolmastercard.sexmod.networking.PacketHandler
import com.trolmastercard.sexmod.networking.SendBlocksPacket
import java.util.Optional
import java.util.UUID
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.monster.AbstractSkeleton
import net.minecraft.world.entity.monster.Spider
import net.minecraft.world.entity.monster.Zombie
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks

/**
 * <b>Role.</b> The kobold tribe system — all tribe state lives here:
 * tribes keyed by UUID ([KoboldManager.Tribe]), each holding members,
 * leader, color, state ([TribeState]), tasks ([KoboldTask]),
 * combatants, beds/chests, saved positions and the follow-mode flag. Used by
 * `KoboldEntity`/`KoboldPlayerEntity` AI and the dragon-staff
 * networking packets (Mine/FallTree/Claim/GetTribeUiValues/...).
 *
 * <b>Persistence.</b> [KoboldManager.TribeWorldSavedData] serializes all
 * tribes (members, saved positions, beds, chests, tasks with mining targets)
 * into the "tribes" world data and also reacts to block place/break (bed/chest
 * tracking) and hostile spawns (girl-targeting AI). [clearAll] on world
 * close.
 *
 * <b>Pitfalls.</b> Many lookups print "tribe not found" and return neutral
 * values — that is the intended fallback, not an error path to fix. Task
 * removal releases workers (gravity/clip/anchor restored) and re-sends the
 * block markers. The bed-count check (members <= beds/2) gates all work
 * commands.
 */
object KoboldManager {
    const val TRIBE_SPAWN_RADIUS: Int = 4

    private val tribesById: HashMap<UUID, Tribe> = HashMap()

    @JvmField
    val SPAWN_OFFSETS: Array<Vec3> = arrayOf(
        Vec3(0.0, 0.0, 0.0), Vec3(0.5, 0.0, 0.0), Vec3(-0.5, 0.0, 0.0), Vec3(0.0, 0.0, 0.5), Vec3(0.0, 0.0, -0.5),
    )

    @JvmField
    var bedAssignments: HashMap<KoboldEntity, Array<BlockPos>> = HashMap()

    @JvmStatic
    fun clearAll() {
        tribesById.clear()
        bedAssignments.clear()
    }

    @JvmStatic
    fun spawnKoboldAt(world: Level, pos: Vec3) {
        val tribeId = UUID.randomUUID()
        val speeds = FloatArray(4)
        speeds[0] = 0.25f

        for (i in 1 until speeds.size) {
            speeds[i] = KoboldEntity.getRandomThrowDelay()
        }

        val kobolds = ArrayList<KoboldEntity>()

        for (speed in speeds) {
            val kobold = KoboldEntity.createKoboldWithSpeed(world, tribeId, speed)
            kobolds.add(kobold)
        }

        val color = EyeAndKoboldColor.values()[Reference.RANDOM.nextInt(EyeAndKoboldColor.values().size)]
        val tribe = Tribe(tribeId, color, kobolds[0], kobolds)
        tribesById[tribeId] = tribe
        var i2 = 0

        for (kobold2 in kobolds) {
            kobold2.setPos(pos.x + SPAWN_OFFSETS[i2].x, pos.y, pos.z + SPAWN_OFFSETS[i2].z)
            world.addFreshEntity(kobold2)
            i2++
        }
    }

    @JvmStatic
    fun doesTribeExist(tribeId: UUID): Boolean = tribesById[tribeId] != null

    @JvmStatic
    fun assignMaster(tribeId: UUID, playerUuid: UUID?) {
        val tribe = tribesById[tribeId]
        tribe?.setMasterPlayerUUID(playerUuid)
    }

    @JvmStatic
    fun setTribeColor(tribeId: UUID, color: EyeAndKoboldColor) {
        val tribe = tribesById[tribeId]
        if (tribe != null) {
            Main.LOGGER.info("tribe of UUID $tribeId does already exist lol")
        } else {
            tribesById[tribeId] = Tribe(tribeId, color)
        }
    }

    @JvmStatic
    fun isBedAssigned(bedPos: BlockPos): Boolean = KoboldBlockLookup.isBedAssigned(bedAssignments, bedPos)

    @JvmStatic
    fun getBedForKobold(kobold: KoboldEntity): Array<BlockPos>? = bedAssignments[kobold]

    @JvmStatic
    fun assignBed(kobold: KoboldEntity, bedPos: BlockPos) {
        val world = kobold.level()
        val headPos = KoboldBlockLookup.findBedHead(world, bedPos)
        if (headPos == null) {
            Main.LOGGER.info("bed @$bedPos apparently doesn't have another half.. wtf")
        } else {
            bedAssignments[kobold] = arrayOf(bedPos, headPos)
        }
    }

    @JvmStatic
    fun setTribeLeader(kobold: KoboldEntity) {
        bedAssignments.remove(kobold)
    }

    @JvmStatic
    fun setLeaderKobold(tribeId: UUID, kobold: KoboldEntity?) {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
        } else {
            tribe.leaderKobold = kobold
        }
    }

    @JvmStatic
    fun addTribeMember(tribeId: UUID, kobold: KoboldEntity) {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
        } else {
            tribe.addMember(kobold)
            tribesById.replace(tribeId, tribe)
            kobold.getEntityData().set(KoboldEntity.TRIBE_ID, Optional.of(tribeId))
            if (!kobold.editedColorManually) {
                kobold.getEntityData().set(KoboldEntity.CURRENT_ACTION, tribe.tribeColor.toString())
            }
        }
    }

    @JvmStatic
    fun triggerFastSexAction(tribeId: UUID) {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
        } else {
            val leader = tribe.leaderKobold
            if (leader == null || leader.isRemoved) {
                tribe.leaderKobold = tribe.getLeaderKobold()
            }
        }
    }

    @JvmStatic
    fun setTribeLeader(tribeId: UUID, kobold: KoboldEntity) {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
        } else {
            tribe.setLeaderKobold(kobold)
            tribe.savePosition(kobold.getGirlId())
            val currentLeader = tribe.leaderKobold
            if (currentLeader != null && currentLeader.id == kobold.id) {
                val oldLeader = tribe.getLeaderKobold()
                if (oldLeader != null) {
                    tribe.leaderKobold = oldLeader
                }
            }

            KoboldTaskBook.addWorkerToAll(tribe.tasks, kobold)

            if (tribe.members.isNotEmpty()) {
                tribesById.replace(tribeId, tribe)
            } else if (kobold.hasMaster()) {
                val master = kobold.getMasterPlayer()
                if (master != null) {
                    val blocks = HashSet<BlockPos>()
                    blocks.addAll(tribe.tribeBeds)
                    blocks.addAll(tribe.tribeChests)

                    for (task2 in tribe.tasks) {
                        blocks.addAll(task2.miningTargets)
                    }

                    PacketHandler.networkWrapper.sendTo(SendBlocksPacket(blocks, false), master as ServerPlayer)
                    master.sendSystemMessage(
                        Component.literal(
                            String.format(
                                "ur %stribe %shas been %seradicated %suwu",
                                ChatFormatting.RED, ChatFormatting.WHITE, ChatFormatting.RED, ChatFormatting.WHITE,
                            ),
                        ),
                    )
                }
            }
        }
    }

    @JvmStatic
    fun getTribeLeader(tribeId: UUID): KoboldEntity? {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return null
        } else {
            return tribe.leaderKobold
        }
    }

    @JvmStatic
    fun isTribeMember(tribeId: UUID, kobold: KoboldEntity): Boolean {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return false
        } else {
            val leader = tribe.leaderKobold
            return if (leader == null) false else leader.id == kobold.id
        }
    }

    @JvmStatic
    fun getTribeColor(tribeId: UUID): EyeAndKoboldColor {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return KoboldEntity.DEFAULT_EYE_COLOR
        } else {
            return tribe.tribeColor
        }
    }

    @JvmStatic
    fun getTribeBeds(tribeId: UUID): HashSet<BlockPos> {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return HashSet()
        } else {
            // jar-verified: the deobf once returned the chest set here (and vice versa
            // in getTribeChests), crossing the whole bed/chest bookkeeping
            return tribe.tribeBeds
        }
    }

    @JvmStatic
    fun addTribeChest(tribeId: UUID, pos: BlockPos?) {
        if (pos != null) {
            val tribe = tribesById[tribeId]
            if (tribe == null) {
                Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            } else {
                tribe.tribeChests.add(pos)
            }
        }
    }

    @JvmStatic
    fun removeTribeChest(tribeId: UUID, pos: BlockPos?) {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
        } else {
            tribe.tribeChests.remove(pos)
        }
    }

    @JvmStatic
    fun getTribeChests(tribeId: UUID): HashSet<BlockPos>? {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return null
        } else {
            return tribe.tribeChests
        }
    }

    @JvmStatic
    fun addTribeBed(tribeId: UUID, pos: BlockPos?) {
        if (pos != null) {
            val tribe = tribesById[tribeId]
            if (tribe == null) {
                Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            } else {
                tribe.tribeBeds.add(pos)
            }
        }
    }

    @JvmStatic
    fun removeTribeBed(tribeId: UUID, pos: BlockPos?) {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
        } else {
            tribe.tribeBeds.remove(pos)
        }
    }

    @JvmStatic
    fun removeTaskAndGetBlocks(tribeId: UUID, task: KoboldTask?): HashSet<BlockPos> {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return HashSet()
        } else if (task != null) {
            tribe.removeTask(task)
            return task.miningTargets
        } else {
            return HashSet()
        }
    }

    @JvmStatic
    fun removeMiningTargetsFor(tribeId: UUID, pos: BlockPos?): HashSet<BlockPos> {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return HashSet()
        }

        val taskToRemove = KoboldTaskBook.findTaskWithTarget(tribe.tasks, pos)
        return removeTaskAndGetBlocks(tribeId, taskToRemove)
    }

    @JvmStatic
    fun addTask(tribeId: UUID, task: KoboldTask) {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
        } else {
            tribe.addTask(task)
        }
    }

    @JvmStatic
    fun removeTaskForKobold(tribeId: UUID, kobold: KoboldEntity) {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
        } else {
            val taskToRemove = KoboldTaskBook.findTaskWithWorker(tribe.tasks, kobold)
            if (taskToRemove == null) {
                Main.LOGGER.warn("task of worker " + kobold.getGirlId() + " not found uwu")
            } else {
                tribe.removeTask(taskToRemove)
            }
        }
    }

    @JvmStatic
    fun getTribeTasks(tribeId: UUID): MutableCollection<KoboldTask>? {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return null
        } else {
            return tribe.tasks
        }
    }

    @JvmStatic
    fun getTribeState(tribeId: UUID): TribeState {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return TribeState.REST
        } else {
            return tribe.getState()
        }
    }

    @JvmStatic
    fun setTribeState(tribeId: UUID, state: TribeState) {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
        } else {
            tribe.setState(state)
        }
    }

    @JvmStatic
    fun getTribeMemberCount(tribeId: UUID): Int {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return 0
        } else {
            return tribe.getTribeId()
        }
    }

    @JvmStatic
    fun getTribeMembersList(tribeId: UUID): MutableList<KoboldEntity> {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return ArrayList()
        } else {
            return tribe.members
        }
    }

    @JvmStatic
    fun setTribeHome(tribeId: UUID, pos: BlockPos?) {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
        } else {
            tribe.setTribeHome(pos)
        }
    }

    @JvmStatic
    fun getTribeHomePos(tribeId: UUID): BlockPos? {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return null
        } else {
            return tribe.getTribeHome()
        }
    }

    @JvmStatic
    fun getTribeTargets(tribeId: UUID): HashSet<LivingEntity> {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return HashSet()
        } else {
            return tribe.getCombatants()
        }
    }

    @JvmStatic
    fun addCombatant(tribeId: UUID, entity: LivingEntity) {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
        } else {
            tribe.addCombatant(entity)
        }
    }

    @JvmStatic
    fun removeCombatant(tribeId: UUID, entity: LivingEntity) {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
        } else {
            tribe.removeCombatant(entity)
        }
    }

    @JvmStatic
    fun hasAssignedMaster(tribeId: UUID): Boolean {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return false
        }

        for (member in tribe.members) {
            if (member.getInteractionPlayerUUID() != null) {
                return true
            }
        }

        return false
    }

    @JvmStatic
    fun isTribeAlerted(tribeId: UUID): Boolean {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return false
        } else {
            return tribe.followModeEnabled
        }
    }

    @JvmStatic
    fun setTribeFollowMode(tribeId: UUID, enabled: Boolean) {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
        } else {
            tribe.followModeEnabled = enabled
        }
    }

    @JvmStatic
    fun getTribeUUID(playerUuid: UUID?): UUID? {
        if (playerUuid == null) {
            return null
        }

        for ((key, tribe) in tribesById) {
            if ((tribe.getSavedPositions().size != 0 || tribe.getTribeId() != 0) && playerUuid == tribe.getMasterPlayerUUID()) {
                return key
            }
        }

        return null
    }

    @JvmStatic
    fun findTribeIdWith(masterUuid: UUID): UUID? {
        val tribe = tribesById[masterUuid]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $masterUuid not found uwu")
            return null
        }

        val members = tribe.members
        if (members.isEmpty()) {
            return null
        }

        val firstMember = members[0]
        if (!firstMember.hasMaster()) {
            return null
        }

        val masterStr = members[0].getEntityData().get(BaseGirlEntity.MASTER)
        return UUID.fromString(masterStr)
    }

    @JvmStatic
    fun getAllTribeBlocks(tribeId: UUID): HashSet<BlockPos> {
        val tribe = tribesById[tribeId]
        val blocks = HashSet<BlockPos>()
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return blocks
        }

        return KoboldBlockLookup.collectTribeBlocks(tribe.tasks, tribe.tribeBeds, tribe.tribeChests)
    }

    @JvmStatic
    fun getTribeSavedPositions(tribeId: UUID, world: Level): HashMap<UUID, BlockPos> {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
            return HashMap()
        }

        var savedPositions = tribe.savedPositions
        savedPositions = KoboldBlockLookup.scanSavedPositions(savedPositions, world)
        tribe.savedPositions = savedPositions
        return savedPositions
    }

    @JvmStatic
    fun setTribeHomeForMember(tribeId: UUID, memberUuid: UUID, homePos: BlockPos) {
        val tribe = tribesById[tribeId]
        if (tribe == null) {
            Main.LOGGER.warn("tribe of UUID $tribeId not found uwu")
        } else {
            tribe.setTribeHomePos(memberUuid, homePos)
        }
    }

    /**
     * <b>Role.</b> A single tribe's mutable state inside [KoboldManager]:
     * master player, leader kobold, members, color, [TribeState], tasks,
     * combatants (hostile mobs the tribe is fighting), registered beds/chests and
     * saved member positions. Methods are only called through [KoboldManager]
     * statics.
     *
     * <b>Pitfalls.</b> [getTribeId] counts *distinct girl UUIDs* (members +
     * saved positions) — it is the value used by the bed-count gate, not the member
     * list size. [removeTask] restores every worker's physics and re-sends
     * the removed mining targets as un-highlight markers. [addMember] drops
     * stale entries with the same girl id (a kobold re-joining after a reload).
     */
    class Tribe {
        @JvmField
        var masterPlayerUUID: UUID? = null

        @JvmField
        var leaderKobold: KoboldEntity? = null

        @JvmField
        var members: MutableList<KoboldEntity>

        @JvmField
        var tribeColor: EyeAndKoboldColor

        @JvmField
        var state: TribeState = TribeState.REST

        @JvmField
        var tribeHome: BlockPos? = null

        @JvmField
        var tasks: MutableCollection<KoboldTask> = ArrayList()

        @JvmField
        var combatants: HashSet<LivingEntity> = HashSet()

        @JvmField
        var tribeBeds: HashSet<BlockPos> = HashSet()

        @JvmField
        var tribeChests: HashSet<BlockPos> = HashSet()

        @JvmField
        var savedPositions: HashMap<UUID, BlockPos> = HashMap()

        @JvmField
        var followModeEnabled: Boolean = false

        constructor(tribeUUID: UUID, tribeColor: EyeAndKoboldColor, leaderKobold: KoboldEntity?, members: MutableList<KoboldEntity>) {
            this.tribeColor = tribeColor
            this.leaderKobold = leaderKobold
            this.members = members
        }

        constructor(tribeUUID: UUID, tribeColor: EyeAndKoboldColor) {
            this.tribeColor = tribeColor
            this.members = ArrayList()
        }

        fun setMasterPlayerUUID(uuid: UUID?) {
            this.masterPlayerUUID = uuid
        }

        fun getMasterPlayerUUID(): UUID? = this.masterPlayerUUID

        fun removeTask(task: KoboldTask) {
            if (this.tasks.contains(task)) {
                for (worker in task.workers) {
                    worker.setCurrentAction(Action.NULL)
                    worker.isNoGravity = false
                    worker.noPhysics = false
                    worker.getEntityData().set(BaseGirlEntity.IS_ANCHORED, false)
                }

                this.tasks.remove(task)
                val masterUuid = this.masterPlayerUUID
                if (task.miningTargets.isNotEmpty() && masterUuid != null) {
                    val master = ServerLifecycleHooks.getCurrentServer()?.playerList?.getPlayer(masterUuid)
                    if (master != null) {
                        PacketHandler.networkWrapper.sendTo(SendBlocksPacket(task.miningTargets, false), master)
                    }
                }
            }
        }

        fun getSavedPositions(): HashMap<UUID, BlockPos> = this.savedPositions

        fun setTribeHomePos(memberUuid: UUID, pos: BlockPos) {
            this.savedPositions[memberUuid] = pos
        }

        fun savePosition(memberUuid: UUID) {
            this.savedPositions.remove(memberUuid)
        }

        fun removeCombatant(entity: LivingEntity) {
            this.combatants.remove(entity)
        }

        fun addCombatant(entity: LivingEntity) {
            this.combatants.add(entity)
        }

        fun getCombatants(): HashSet<LivingEntity> = this.combatants

        fun getTribeId(): Int {
            val distinctIds = HashSet<UUID>()

            for (member in this.members) {
                distinctIds.add(member.getGirlId())
            }

            for (entry in this.savedPositions.entries) {
                distinctIds.add(entry.key)
            }

            return distinctIds.size
        }

        fun getTribeHome(): BlockPos? = this.tribeHome

        fun setTribeHome(pos: BlockPos?) {
            this.tribeHome = pos
        }

        fun addTask(task: KoboldTask) {
            this.tasks.add(task)
        }

        fun getState(): TribeState = this.state

        fun setState(state: TribeState) {
            this.state = state
        }

        fun addMember(kobold: KoboldEntity) {
            if (!this.members.contains(kobold)) {
                val girlId = kobold.getGirlId()
                val staleMembers = ArrayList<KoboldEntity>()

                for (member in this.members) {
                    if (member.getGirlId() == girlId) {
                        staleMembers.add(member)
                    }
                }

                for (stale in staleMembers) {
                    Main.LOGGER.warn(
                        String.format(
                            "Removed old entry of kobold called %s with UUID %s owned by %s",
                            stale.getDisplayNameText(), stale.getGirlId(), this.masterPlayerUUID,
                        ),
                    )
                    this.setLeaderKobold(stale)
                }

                this.members.add(kobold)
            }
        }

        fun setLeaderKobold(kobold: KoboldEntity) {
            this.members.remove(kobold)
        }

        fun getLeaderKobold(): KoboldEntity? {
            var fastest: KoboldEntity? = null

            for (member in this.members) {
                if (!member.isRemoved) {
                    if (fastest == null) {
                        fastest = member
                    } else {
                        val fastestSpeed = fastest.getEntityData().get(KoboldEntity.SIZE_SCALAR)
                        val memberSpeed = member.getEntityData().get(KoboldEntity.SIZE_SCALAR)
                        if (memberSpeed < fastestSpeed) {
                            fastest = member
                        }
                    }
                }
            }

            return fastest
        }
    }

    /**
     * <b>Role.</b> World persistence + tribe block/combat events for
     * [KoboldManager]: saves/loads all tribes under the "tribes" world-data
     * key, blocks players from sleeping in assigned kobold beds, tracks bed/chest
     * placement and breaking against the tribe sets (with marker echoes to the
     * master), and gives zombies/skeletons/spiders a girl-targeting AI goal.
     *
     * <b>Pitfall.</b> The NBT layout is string-indexed (`tribeId0`,
     * `<tribeId>member0pos`, `<tribeId>0taskKind`, ...) — read and write
     * must stay in lockstep; [readNBTValue] consumes (empties) keys as it
     * reads, so a re-read of the same compound returns nothing.
     *
     * 1.21: a [SavedData] in the overworld data storage (file `tribes`). The
     * instance registered on the event bus is the one stored on save, like the
     * 1.12 `setData("tribes", this)`; loading builds a throwaway instance whose
     * only job is to fill the static tribe map.
     */
    class TribeWorldSavedData : SavedData {
        /** Kept for the 1.12 call site (`new TribeWorldSavedData("tribes")`). */
        constructor(dataId: String) : super()

        constructor() : super()

        @SubscribeEvent
        fun onWorldSave(event: LevelEvent.Save) {
            val world = event.level as? ServerLevel ?: return
            world.server.overworld().dataStorage.set(DATA_NAME, this)
            this.setDirty()
        }

        @SubscribeEvent
        fun onWorldLoad(event: LevelEvent.Load) {
            val world = event.level as? ServerLevel ?: return
            world.server.overworld().dataStorage.computeIfAbsent(factory(), DATA_NAME)
        }

        @SubscribeEvent
        fun onPlayerSleepInBed(event: CanPlayerSleepEvent) {
            if (isBedAssigned(event.pos)) {
                event.problem = Player.BedSleepingProblem.OTHER_PROBLEM
            }
        }

        @SubscribeEvent
        fun onBlockPlace(event: BlockEvent.EntityPlaceEvent) {
            val world = event.level as? Level ?: return
            TribeBlockTracking.place(world, event.pos, event.placedBlock, tribesById)
        }

        @SubscribeEvent
        fun onEntityJoinWorld(event: EntityJoinLevelEvent) {
            val entity = event.entity
            if (entity is Zombie) {
                entity.targetSelector.addGoal(3, NearestAttackableGirlGoal(entity, true, false))
            }

            if (entity is AbstractSkeleton) {
                entity.targetSelector.addGoal(3, NearestAttackableGirlGoal(entity, true, false))
            }

            if (entity is Spider) {
                entity.targetSelector.addGoal(3, NearestAttackableGirlGoal(entity, true, true))
            }
        }

        @SubscribeEvent
        fun onBlockBreak(event: BlockEvent.BreakEvent) {
            val world = event.level as? Level ?: return
            TribeBlockTracking.breakBlock(world, event.pos, tribesById)
        }

        fun readNBTValue(key: String, nbt: CompoundTag): String = TribeNbtIO.readNBTValue(key, nbt)

        fun readFromNBT(nbt: CompoundTag) {
            TribeNbtIO.read(nbt)
        }

        fun writeToNBT(nbt: CompoundTag): CompoundTag = TribeNbtIO.write(nbt, tribesById)

        override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = this.writeToNBT(tag)

        /** I/O shell over static state: always dirty so every overworld data save writes the statics (LevelEvent.Save fires after the data storage is flushed). */
        override fun isDirty(): Boolean = true

        companion object {
            const val DATA_NAME: String = "tribes"

            @JvmStatic
            fun load(tag: CompoundTag, registries: HolderLookup.Provider): TribeWorldSavedData {
                val data = TribeWorldSavedData()
                data.readFromNBT(tag)
                return data
            }

            @JvmStatic
            fun factory(): SavedData.Factory<TribeWorldSavedData> = SavedData.Factory({ TribeWorldSavedData() }, ::load, null)
        }
    }
}
