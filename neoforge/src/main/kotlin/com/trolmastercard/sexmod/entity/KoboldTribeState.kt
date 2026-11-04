package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.block.SexFireBlock
import com.trolmastercard.sexmod.item.DragonStaffItem
import com.trolmastercard.sexmod.item.KoboldEggItem
import com.trolmastercard.sexmod.util.ItemNbt
import com.trolmastercard.sexmod.util.KoboldManager
import com.trolmastercard.sexmod.util.KoboldTask
import com.trolmastercard.sexmod.util.Reference
import com.trolmastercard.sexmod.util.RotationHelper
import com.trolmastercard.sexmod.util.TribeState
import com.trolmastercard.sexmod.util.WorldUtils
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.tags.BlockTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Tribe bookkeeping for [KoboldEntity]: the day/night REST/ACTIVE
 * schedule, task (re)assignment, bed and home requests, tribe combat, leader
 * and member sync, and the master-presence follow loop.
 *
 * All tribe state is held by `KoboldManager` keyed by the entity's tribe
 * UUID; the fields this code touches on the entity are the
 * navigation scratch state (`tribeWanderTarget` wander target, `homeApproachPos` home offset,
 * `lastStuckCheckPos` last position, `tribeTasksInitialized` work flag).
 *
 * <b>Pitfalls.</b> `handleTribeRequest` runs on both sides but writes
 * tribe state only through `KoboldManager`, and the `DIST` checks
 * are in blocks (not squared) on purpose.
 *
 * 1.21 notes: `BlockLog` scans are `RotatedPillarBlock` scans filtered by the
 * `#minecraft:logs` tag; the 1.12 `getWorldTime()` (total day time, not modulo
 * a day) is `level.dayTime`; the egg item metadata is [ItemNbt.setMeta].
 */
object KoboldTribeState {
    private fun distInt(pos: BlockPos, x: Double, y: Double, z: Double): Double =
        // 1.12 BlockPos.getDistance(int, int, int) with (int)-truncated entity coordinates
        Math.sqrt(pos.distSqr(Vec3i(x.toInt(), y.toInt(), z.toInt())))

    @JvmStatic
    fun handleActionCooldown(entity: KoboldEntity, tribeId: UUID) {
        if (entity.actionCooldown != -1) {
            entity.actionCooldown++
            if (entity.actionCooldown >= 132) {
                entity.actionCooldown = -1
                if (entity.getCurrentAction() == Action.MATING_PRESS_CUM) {
                    val uuid = entity.getInteractionPlayerUUID()
                    if (uuid != null) {
                        val player = entity.level().getPlayerByUUID(uuid)
                        if (player != null) {
                            val color = KoboldManager.getTribeColor(tribeId)
                            val eggStack = ItemStack(KoboldEggItem.KOBOLD_EGG_ITEM, 1)
                            ItemNbt.setMeta(eggStack, color.getWoolMeta())
                            var nbt = ItemNbt.getTag(eggStack)
                            if (nbt == null) {
                                nbt = CompoundTag()
                            }

                            nbt.putString("tribeID", tribeId.toString())
                            nbt.putString("tribeColor", color.toString())
                            ItemNbt.setTag(eggStack, nbt)
                            player.inventory.add(eggStack)
                        }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun handleTribeState(entity: KoboldEntity) {
        if (!entity.getEntityData().get(KoboldEntity.ARMED)) {
            if (!entity.hasMaster()) {
                val tribeIdOpt = entity.getEntityData().get(KoboldEntity.TRIBE_ID)
                if (tribeIdOpt.isPresent) {
                    for (player in entity.level().players()) {
                        val dist = player.position().distanceTo(entity.position())
                        var closestDist = dist
                        if (!entity.level().isClientSide) {
                            for (kobold in KoboldManager.getTribeMembersList(tribeIdOpt.get())) {
                                val koboldDist = player.position().distanceTo(kobold.position())
                                if (koboldDist < closestDist) {
                                    closestDist = koboldDist
                                }
                            }
                        }

                        if (!(closestDist > 10.0)) {
                            if (player.getItemInHand(InteractionHand.MAIN_HAND).item !== DragonStaffItem.DRAGON_STAFF &&
                                player.getItemInHand(InteractionHand.OFF_HAND).item !== DragonStaffItem.DRAGON_STAFF
                            ) {
                                continue
                            }

                            val navigator = entity.getNavigation()
                            navigator.stop()
                            if (entity.level().isClientSide) {
                                entity.handleKoboldOwner(player)
                            } else if (dist > 2.0) {
                                val standPos = entity.findStandPos(player.blockPosition())
                                navigator.moveTo(standPos.x.toDouble(), standPos.y.toDouble(), standPos.z.toDouble(), 0.35f.toDouble())
                            }

                            return
                        }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun handleMasterPresence(entity: KoboldEntity) {
        val tribeIdOpt = entity.getEntityData().get(KoboldEntity.TRIBE_ID)
        if (tribeIdOpt.isPresent) {
            val tribeId = tribeIdOpt.get()
            if (!entity.getEntityData().get(KoboldEntity.ARMED) && KoboldManager.isTribeAlerted(tribeId)) {
                if (!entity.hasMaster()) {
                    return
                }

                val master = entity.getMasterPlayer() ?: return

                for (task in KoboldManager.getTribeTasks(tribeId)!!) { // java-npe
                    if (task.hasWorker(entity)) {
                        task.removeWorker(entity)
                        entity.setCurrentAction(Action.NULL)
                        entity.getEntityData().set(BaseGirlEntity.IS_ANCHORED, false)
                    }
                }

                entity.noPhysics = false
                entity.isNoGravity = false
                val navigator = entity.getNavigation()
                val dist = entity.position().distanceTo(master.position())
                if (dist > 2.0) {
                    navigator.moveTo(master, getKickDistance(entity, master, dist))
                    entity.tickPathVelocity()
                    if (dist > 15.0) {
                        handlePlayerDismount(entity, master)
                    }
                }
            } else if (KoboldManager.isTribeMember(tribeId, entity)) {
                handleTribeRequest(entity, tribeId)
            } else {
                handleTribeJoin(entity, tribeId)
            }
        }
    }

    @JvmStatic
    fun getKickDistance(entity: KoboldEntity, player: Player, dist: Double): Double {
        var kickDistance = if (player.isSprinting) {
            0.7
        } else {
            0.35
        }

        val extra = Math.floor(dist / 5.0) * 0.3
        kickDistance += extra
        if (entity.isInWater) {
            kickDistance *= 60.0
        }

        return kickDistance
    }

    @JvmStatic
    fun teleportToHome(entity: KoboldEntity, tribeId: UUID) {
        val homePos = KoboldManager.getTribeHomePos(tribeId)
        if (homePos != null) {
            val saved = entity.savedHomeBlockState
            if (saved != null) {
                entity.level().setBlockAndUpdate(homePos, saved)
            }

            val below = entity.blockBelowState
            if (below != null) {
                entity.level().setBlockAndUpdate(homePos.offset(0, -1, 0), below)
            }
        }
    }

    @JvmStatic
    fun handleTribeRequest(entity: KoboldEntity, tribeId: UUID) {
        if (!isTribeTaskDone(entity, tribeId)) {
            if (!entity.hasMaster() && KoboldManager.hasAssignedMaster(tribeId)) {
                entity.getNavigation().stop()
                entity.tribeWanderTarget = null
            } else {
                val currentState = KoboldManager.getTribeState(tribeId)
                val newState = getTribeStateForTime(entity)
                if (currentState != newState) {
                    KoboldManager.setTribeState(tribeId, newState)
                    when (newState) {
                        TribeState.REST -> {
                            handleTaskAssign(entity, tribeId)
                            KoboldManager.setTribeHome(tribeId, null)
                            entity.sendGirlChatMessage("okay resting time owo")
                        }
                        TribeState.ACTIVE -> {
                            teleportToHome(entity, tribeId)
                            handleMemberSync(entity, tribeId)
                        }
                    }
                }

                when (newState) {
                    TribeState.REST -> handleTaskRequest(entity, tribeId)
                    TribeState.ACTIVE -> {
                        entity.homeApproachPos = null
                        handleHomeRelease(entity, tribeId)
                    }
                }
            }
        }
    }

    @JvmStatic
    fun handleTaskAssign(entity: KoboldEntity, tribeId: UUID) {
        val tasks = KoboldManager.getTribeTasks(tribeId)
        if (tasks != null) {
            for (task in tasks) {
                task.releaseWorkers()
            }
        }
    }

    @JvmStatic
    fun handleMemberSync(entity: KoboldEntity, tribeId: UUID) {
        if (entity.hasMaster()) {
            for (kobold in KoboldManager.getTribeMembersList(tribeId)) {
                KoboldManager.setTribeLeader(kobold)
                if (kobold.getInteractionPlayerUUID() == null) {
                    kobold.noPhysics = false
                    kobold.isNoGravity = false
                    kobold.getEntityData().set(BaseGirlEntity.IS_ANCHORED, false)
                    kobold.setCurrentAction(Action.NULL)
                }
            }
        }
    }

    @JvmStatic
    fun handleTaskRequest(entity: KoboldEntity, tribeId: UUID) {
        val tasks = KoboldManager.getTribeTasks(tribeId)
        if (tasks != null) {
            for (task in tasks) {
                task.addWorker(entity)
            }
        }

        if (entity.hasMaster()) {
            handleBedRequest(entity, tribeId)
        } else {
            handleHomeRequest(entity, tribeId)
        }
    }

    @JvmStatic
    fun handleBedRequest(entity: KoboldEntity, tribeId: UUID) {
        val bedPositions = KoboldManager.getBedForKobold(entity)
        if (bedPositions != null) {
            val headVec = Vec3(bedPositions[0].x + 0.5, bedPositions[0].y + 0.5625, bedPositions[0].z + 0.5)
            val footVec = Vec3(bedPositions[1].x + 0.5, bedPositions[1].y + 0.5625, bedPositions[1].z + 0.5)
            val isVertical = headVec.subtract(footVec).x == 0.0
            val midVec = RotationHelper.lerpVec3dDouble(headVec, footVec, 0.5)
            entity.getEntityData().set(BaseGirlEntity.IS_ANCHORED, true)
            entity.setTargetPosition(midVec)
            entity.setYawRotation(if (isVertical) 0.0f else 90.0f)
            entity.noPhysics = true
            entity.isNoGravity = true
        } else {
            val beds = KoboldManager.getTribeBeds(tribeId)
            var chosenBed: BlockPos? = null
            for (bedPos in beds) {
                val state = entity.level().getBlockState(bedPos)
                var occupied = false

                for ((key, value) in state.values) {
                    if (key is BooleanProperty) {
                        occupied = value as Boolean
                        break
                    }
                }

                if (!occupied && !KoboldManager.isBedAssigned(bedPos)) {
                    if (chosenBed == null) {
                        chosenBed = bedPos
                    } else if (chosenBed.distToLowCornerSqr(entity.x, entity.y, entity.z) > bedPos.distToLowCornerSqr(entity.x, entity.y, entity.z)) {
                        chosenBed = bedPos
                    }
                }
            }

            if (chosenBed != null) {
                if (distInt(chosenBed, entity.x, entity.y, entity.z) > 2.0) {
                    if (Math.abs(chosenBed.subtract(entity.blockPosition()).y) > 4) {
                        entity.syncTribeBlocks(chosenBed.offset(0, 1, 0))
                    } else {
                        val standPos = entity.findStandPos(chosenBed)
                        entity.getNavigation().moveTo(standPos.x.toDouble(), standPos.y.toDouble(), standPos.z.toDouble(), 0.35f.toDouble())
                        if (entity.getNavigation().path == null) {
                            entity.syncTribeBlocks(chosenBed.offset(0, 1, 0))
                        }
                    }
                } else {
                    KoboldManager.assignBed(entity, chosenBed)
                    entity.setCurrentAction(Action.SLEEP)
                }
            }
        }
    }

    @JvmStatic
    fun handleHomeRequest(entity: KoboldEntity, tribeId: UUID) {
        val homePos = KoboldManager.getTribeHomePos(tribeId)
        if (homePos != null) {
            var approach = entity.homeApproachPos
            if (approach == null) {
                approach = homePos.offset(
                    (if (entity.random.nextBoolean()) 1 else -1) * (entity.random.nextInt(2) + 1),
                    0,
                    (if (entity.random.nextBoolean()) 1 else -1) * (entity.random.nextInt(2) + 1),
                )
                entity.homeApproachPos = approach
            }

            entity.getNavigation().moveTo(approach.x.toDouble(), approach.y.toDouble(), approach.z.toDouble(), 0.35f.toDouble())
            entity.tickPathVelocity()
        } else {
            if (KoboldManager.isTribeMember(tribeId, entity)) {
                val pos = entity.blockPosition().offset(1, 0, 0)
                entity.blockBelowState = entity.level().getBlockState(pos.offset(0, -1, 0))
                entity.savedHomeBlockState = entity.level().getBlockState(pos)
                entity.level().setBlockAndUpdate(pos.offset(0, -1, 0), Blocks.NETHERRACK.defaultBlockState())
                entity.level().setBlockAndUpdate(pos, SexFireBlock.FIRE.defaultBlockState())
                KoboldManager.setTribeHome(tribeId, pos)
            }
        }
    }

    @JvmStatic
    fun handleHomeRelease(entity: KoboldEntity, tribeId: UUID) {
        if (entity.hasMaster()) {
            KoboldManager.setTribeHome(tribeId, null)
            handleTaskFollow(entity, tribeId)
        } else {
            val tasks = KoboldManager.getTribeTasks(tribeId)
            if (tasks != null) {
                if (entity.tribeTasksInitialized) {
                    entity.tribeWanderTarget = null
                    handleTribeTasks(entity, tribeId, tasks)
                } else {
                    handleTribeTasksInit(entity, tribeId, tasks)
                }
            }
        }
    }

    @JvmStatic
    fun handleTribeTasks(entity: KoboldEntity, tribeId: UUID, tasks: Collection<KoboldTask>) {
        if (tasks.isEmpty()) {
            entity.tribeTasksInitialized = false
            checkTribeHome(entity, tribeId)
            entity.sendGirlChatMessage("Lets go somewhere else")
        }
    }

    @JvmStatic
    fun handleTribeTasksInit(entity: KoboldEntity, tribeId: UUID, tasks: Collection<KoboldTask>) {
        val homePos = KoboldManager.getTribeHomePos(tribeId)
        if (homePos == null) {
            checkTribeHome(entity, tribeId)
        } else {
            if (entity.tickCount % 40 == 0) {
                if (entity.lastStuckCheckPos == entity.position()) {
                    checkTribeHome(entity, tribeId)
                    entity.tribeWanderTarget = null
                }

                entity.lastStuckCheckPos = entity.position()
            }

            var target = entity.tribeWanderTarget
            if (target == null || distInt(target, entity.x, entity.y, entity.z) < 4.0) {
                target = getTribeHomePos(entity, tribeId)
                entity.tribeWanderTarget = target
            }

            entity.getNavigation().moveTo(target.x.toDouble(), target.y.toDouble(), target.z.toDouble(), 0.35f.toDouble())
            entity.tickPathVelocity()
            if (!(Math.sqrt(entity.blockPosition().distSqr(homePos)) > 5.0)) {
                entity.tribeTasksInitialized = true
                entity.sendGirlChatMessage("Time to work bitches!")
                val memberCount = KoboldManager.getTribeMemberCount(tribeId)

                for (i in 0 until memberCount) {
                    findConnectedLogs(entity, tribeId, tasks)
                }

                KoboldManager.setTribeHome(tribeId, null)
            }
        }
    }

    @JvmStatic
    fun handlePlayerDismount(entity: KoboldEntity, player: Player) {
        var attempts = 0

        var teleportPos: BlockPos
        do {
            teleportPos = player.blockPosition().offset(Reference.RANDOM.nextInt(10), 0, Reference.RANDOM.nextInt(10))
            attempts++
        } while (attempts < 20 && !entity.randomTeleport(teleportPos.x.toDouble(), teleportPos.y.toDouble(), teleportPos.z.toDouble(), false))

        if (attempts == 20) {
            entity.setPos(player.x, player.y, player.z)
        }

        entity.setDeltaMovement(0.0, 0.0, 0.0)
    }

    @JvmStatic
    fun getTribeHomePos(entity: KoboldEntity, tribeId: UUID): BlockPos {
        val homePos = KoboldManager.getTribeHomePos(tribeId)
        return if (homePos == null) BlockPos.ZERO else entity.findStandPos(homePos)
    }

    @JvmStatic
    fun checkTribeHome(entity: KoboldEntity, tribeId: UUID) {
        var attempts = 0

        var homePos: BlockPos
        do {
            attempts++
            homePos = entity.blockPosition()
            homePos = homePos.offset(
                (50 + entity.random.nextInt(50)) * (if (entity.random.nextBoolean()) 1 else -1),
                0,
                (50 + entity.random.nextInt(50)) * (if (entity.random.nextBoolean()) 1 else -1),
            )
            homePos = BlockPos(homePos.x, WorldUtils.getHeightAt(entity.level(), homePos.x, homePos.z), homePos.z)
            // 1.12 PathNavigate.canEntityStandOnPos == 1.21 isStableDestination (solid block below)
        } while ((homePos.y <= 0 || !entity.getNavigation().isStableDestination(homePos)) && attempts < 100)

        KoboldManager.setTribeHome(tribeId, homePos)
    }

    @JvmStatic
    fun findConnectedLogs(entity: KoboldEntity, tribeId: UUID, tasks: Collection<KoboldTask>) {
        // 1.12 BlockLog.class: logs are RotatedPillarBlocks in the #minecraft:logs tag
        val logs = entity.findBlocksInRadius(entity.blockPosition(), RotatedPillarBlock::class.java, 30, 4, null)
            .filter { entity.level().getBlockState(it).`is`(BlockTags.LOGS) }
        var connectedLog: BlockPos? = null

        for (logPos in logs) {
            val blockBelow = entity.level().getBlockState(logPos.below())
            if (!blockBelow.`is`(BlockTags.LOGS) && blockBelow.block !== Blocks.AIR) {
                var connected = false

                for (task in tasks) {
                    if (task.isMiningTarget(logPos)) {
                        connected = true
                        break
                    }
                }

                if (!connected) {
                    connectedLog = logPos
                    break
                }
            }
        }

        if (connectedLog != null) {
            KoboldTask.findConnectedBlocks(entity.level(), connectedLog, tribeId)
            entity.sendGirlChatMessage("Someone, go fall this tree!")
        }
    }

    @JvmStatic
    fun getTribeStateForTime(entity: KoboldEntity): TribeState {
        val time = entity.level().dayTime
        return if (time < 12000L) TribeState.ACTIVE else TribeState.REST
    }

    @JvmStatic
    fun isTribeTaskDone(entity: KoboldEntity, tribeId: UUID): Boolean = handleTribeCombat(entity, tribeId, true)

    @JvmStatic
    fun handleTribeCombat(entity: KoboldEntity, tribeId: UUID, isLeader: Boolean): Boolean {
        val targets = KoboldManager.getTribeTargets(tribeId)
        val leader = KoboldManager.getTribeLeader(tribeId) ?: return false

        for (kobold in entity.level().getEntitiesOfClass(
            KoboldEntity::class.java,
            AABB(leader.x - 30.0, leader.y - 30.0, leader.z - 30.0, leader.x + 30.0, leader.y + 30.0, leader.z + 30.0),
        )) {
            if (entity.hasLineOfSight(kobold) && (!kobold.hasMaster() || !entity.hasMaster())) {
                val tribeOpt = kobold.getEntityData().get(KoboldEntity.TRIBE_ID)
                if (!tribeOpt.isPresent) {
                    targets.add(kobold)
                } else if (tribeOpt.get() != tribeId) {
                    targets.add(kobold)
                }
            }
        }

        var closestTarget: LivingEntity? = null
        val deadTargets = ArrayList<LivingEntity>()

        for (target in targets) {
            if (target.isRemoved) {
                deadTargets.add(target)
            } else if (!(leader.distanceTo(target) > 30.0f) && (closestTarget == null || entity.distanceTo(closestTarget) > entity.distanceTo(target))) {
                closestTarget = target
            }
        }

        for (target in deadTargets) {
            KoboldManager.removeCombatant(tribeId, target)
        }

        if (closestTarget == null) {
            return false
        }

        if (!isLeader) {
            return true
        }

        if (entity.getCurrentAction() != Action.ATTACK) {
            entity.getEntityData().set(BaseGirlEntity.IS_ANCHORED, false)
            entity.setCurrentAction(Action.NULL)
        }

        val standPos = entity.findStandPos(closestTarget.blockPosition())
        entity.getNavigation().moveTo(standPos.x.toDouble(), standPos.y.toDouble(), standPos.z.toDouble(), 0.7)
        entity.tickPathVelocity()
        if (entity.distanceTo(closestTarget) > 1.5f) {
            return true
        }

        if (entity.attackCooldown > 0) {
            return true
        }

        val yaw = (Math.atan2(entity.z - closestTarget.z, entity.x - closestTarget.x) * (180.0 / Math.PI) + 90.0).toFloat()
        entity.setYawRotation(yaw)
        entity.setCurrentAction(Action.ATTACK)
        entity.attackCooldown = 84
        return true
    }

    @JvmStatic
    fun handleTribeJoin(entity: KoboldEntity, tribeId: UUID) {
        if (!isTribeTaskDone(entity, tribeId)) {
            val state = KoboldManager.getTribeState(tribeId)
            when (state) {
                TribeState.REST -> handleTaskRequest(entity, tribeId)
                TribeState.ACTIVE -> {
                    entity.homeApproachPos = null
                    handleHomeTeleport(entity, tribeId)
                }
            }
        }
    }

    @JvmStatic
    fun handleHomeTeleport(entity: KoboldEntity, tribeId: UUID) {
        val homePos = KoboldManager.getTribeHomePos(tribeId)
        if (homePos == null) {
            entity.tribeWanderTarget = null
            handleTaskFollow(entity, tribeId)
        } else {
            val leader = KoboldManager.getTribeLeader(tribeId)
            if (KoboldManager.hasAssignedMaster(tribeId)) {
                entity.getNavigation().stop()
                entity.tribeWanderTarget = null
            } else if (leader == null) {
                Main.LOGGER.info("leader of tribe $tribeId is null")
            } else {
                if (leader.distanceTo(entity) > 20.0f) {
                    entity.setPos(leader.x, leader.y, leader.z)
                    entity.tribeWanderTarget = null
                }

                if (entity.tickCount % 40 == 0) {
                    if (entity.lastStuckCheckPos == entity.position()) {
                        entity.tribeWanderTarget = getTribeHomePos(entity, tribeId)
                    }

                    entity.lastStuckCheckPos = entity.position()
                }

                var target = entity.tribeWanderTarget
                if (target == null || distInt(target, entity.x, entity.y, entity.z) < 4.0) {
                    target = getTribeHomePos(entity, tribeId)
                    entity.tribeWanderTarget = target
                }

                entity.getNavigation().moveTo(target.x.toDouble(), target.y.toDouble(), target.z.toDouble(), 0.35f.toDouble())
                entity.tickPathVelocity()
            }
        }
    }

    @JvmStatic
    fun handleTaskFollow(entity: KoboldEntity, tribeId: UUID) {
        if (entity.getInteractionPlayerUUID() == null) {
            val tasks = KoboldManager.getTribeTasks(tribeId)
            if (tasks != null) {
                var assignedTask: KoboldTask? = null

                for (task in tasks) {
                    if (task.hasWorker(entity)) {
                        assignedTask = task
                        break
                    }
                }

                if (assignedTask == null) {
                    for (task in tasks) {
                        if (!entity.hasMaster() || assignTaskToKobold(entity, tribeId, task)) {
                            if (!entity.canAssignTask(task)) {
                                entity.tribeBlocked = true
                            } else if (task.addWorker(entity)) {
                                assignedTask = task
                                entity.miningTargetPos = null
                                if (task.getTaskType() == KoboldTask.TaskType.FALL_TREE) {
                                    entity.sendGirlChatMessage("Ima fall this tree owo")
                                } else {
                                    entity.sendGirlChatMessage("Ima go mine uwu")
                                    entity.syncTribeBlocks(task.getTargetPos())
                                    entity.level().setBlockAndUpdate(task.getTargetPos(), Blocks.AIR.defaultBlockState())
                                }
                                break
                            }
                        }
                    }
                }

                if (assignedTask == null) {
                    entity.handleNearbyPlayerTick(tribeId)
                } else {
                    if (assignedTask.getTaskType() == KoboldTask.TaskType.FALL_TREE) {
                        entity.startMiningTask(tribeId, assignedTask.getTargetPos(), assignedTask)
                    }

                    if (assignedTask.getTaskType() == KoboldTask.TaskType.MINE) {
                        handleTribeTasks(entity, tribeId, tasks)
                    }
                }
            }
        }
    }

    @JvmStatic
    fun assignTaskToKobold(entity: KoboldEntity, tribeId: UUID, task: KoboldTask): Boolean {
        val members = KoboldManager.getTribeMembersList(tribeId)
        val tasks = KoboldManager.getTribeTasks(tribeId)!! // java-npe
        var closestKobold: KoboldEntity? = null
        val taskPos = Vec3(task.getTargetPos().x.toDouble(), task.getTargetPos().y.toDouble(), task.getTargetPos().z.toDouble())

        for (kobold in members) {
            var taskAssigned = false

            for (workerTask in tasks) {
                if (workerTask.hasWorker(kobold)) {
                    taskAssigned = true
                    break
                }
            }

            if (!taskAssigned && kobold.getInteractionPlayerUUID() == null) {
                if (closestKobold == null) {
                    closestKobold = kobold
                } else if (closestKobold.position().distanceTo(taskPos) > kobold.position().distanceTo(taskPos)) {
                    closestKobold = kobold
                }
            }
        }

        return entity == closestKobold
    }

    @JvmStatic
    fun handleModelSync(entity: KoboldEntity) {
        if (!entity.editedColorManually) {
            val tribeIdOpt = entity.getEntityData().get(KoboldEntity.TRIBE_ID)
            if (tribeIdOpt.isPresent) {
                entity.getEntityData().set(KoboldEntity.CURRENT_ACTION, KoboldManager.getTribeColor(tribeIdOpt.get()).toString())
            }
        }
    }
}
