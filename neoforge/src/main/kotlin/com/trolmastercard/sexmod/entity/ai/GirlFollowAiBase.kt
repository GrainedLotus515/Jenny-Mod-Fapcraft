package com.trolmastercard.sexmod.entity.ai

import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.util.Reference
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.navigation.PathNavigation
import net.minecraft.world.entity.player.Player
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent

/**
 * <b>Role.</b> Base class for the girl follow goals. Runs while the girl has
 * a master UUID in the `MASTER` synced key; the subclass state
 * machine picks one of [GirlFollowAiBaseState.ATTACK]/`FOLLOW`/
 * `IDLE`/`RIDE`/`DOWNED` each tick. Shared helpers:
 * teleport-fallback navigation ([updateNavigation]), speed/state
 * derivation from the master's sprint state ([getFollowDistance]).
 *
 * <b>Pitfalls.</b> [stop] (1.12 `resetTask`) clears the master binding and
 * nulls the cached navigator/data-manager — a reset followed by a re-start
 * re-derives them in [start]. The [onLivingDeath] handler cancels girl deaths
 * while a master is bound. `stop` sets [Action.NULL] via
 * `setCurrentAction` — routed through the packet on the client.
 *
 * Port notes: 1.12 `shouldExecute`/`shouldContinueExecuting`/`startExecuting`/
 * `resetTask`/`updateTask` are [canUse]/[canContinueToUse]/[start]/[stop]/[tick];
 * the goal ticks every game tick like 1.12 ([requiresUpdateEveryTick]).
 * `PathNavigate.getPathSearchRange()` is the girl's `FOLLOW_RANGE` attribute.
 * [girl] is an open `val` so [GirlFollowGoal] can narrow its type (1.12 field
 * hiding); the constructor itself only uses the parameter.
 */
abstract class GirlFollowAiBase(girl: BaseGirlEntity) : Goal() {
    open val girl: BaseGirlEntity = girl

    @JvmField
    var master: Player? = null

    @JvmField
    var navigator: PathNavigation? = girl.getNavigation()

    @JvmField
    var dataManager: SynchedEntityData? = girl.getEntityData()

    @JvmField
    var state: GirlFollowAiBaseState = GirlFollowAiBaseState.IDLE

    override fun requiresUpdateEveryTick(): Boolean = true

    /**
     * Teleport-fallback navigation: tries up to 20 random offsets around the
     * master; if none is teleportable the girl snaps onto the master's
     * position. Always zeroes her motion afterwards.
     */
    open fun updateNavigation() {
        val master = this.master!! // java-npe
        var attempts = 0

        var targetPos: BlockPos
        do {
            targetPos = master.blockPosition().offset(Reference.RANDOM.nextInt(10), 0, Reference.RANDOM.nextInt(10))
        } while (++attempts < 20 && !this.girl.randomTeleport(targetPos.x.toDouble(), targetPos.y.toDouble(), targetPos.z.toDouble(), false))

        if (attempts >= 20) {
            this.girl.setPos(master.x, master.y, master.z)
        }

        this.girl.setDeltaMovement(0.0, 0.0, 0.0)
    }

    open fun getFollowDistance(): Double {
        val master = this.master!! // java-npe
        val dist = this.girl.distanceTo(master)
        var followDistance: Double
        var state: BaseGirlEntity.BaseGirlEntityState
        if (master.isSprinting) {
            followDistance = 0.7
            state = BaseGirlEntity.BaseGirlEntityState.RUN
        } else {
            followDistance = 0.5
            state = BaseGirlEntity.BaseGirlEntityState.WALK
        }

        val extra = Math.floor((dist / 5.0f).toDouble()) * 0.2
        followDistance += extra
        if (this.girl.isInWater) {
            followDistance *= 60.0
            state = BaseGirlEntity.BaseGirlEntityState.WALK
        }

        this.navigator!!.setSpeedModifier(followDistance) // java-npe
        this.girl.setWalkSpeed(state)
        return followDistance
    }

    override fun stop() {
        this.navigator!!.stop() // java-npe
        this.state = GirlFollowAiBaseState.IDLE
        this.girl.setCurrentAction(Action.NULL)
        this.dataManager!!.set(BaseGirlEntity.MASTER, "") // java-npe
        this.navigator = null
        this.dataManager = null
        this.master = null
    }

    override fun canUse(): Boolean = this.resolveMaster() != null

    override fun canContinueToUse(): Boolean = this.resolveMaster() != null

    override fun start() {
        this.navigator = this.girl.getNavigation()
        this.dataManager = this.girl.getEntityData()
        this.master = this.resolveMaster()
    }

    /**
     * Resolves the master the goal must operate on, or `null` when the
     * data manager holds no master / an unparsable one / a player that is not
     * in this world (offline or another dimension). Every entry point resolves
     * through here so [master] can never be null while the goal runs.
     */
    private fun resolveMaster(): Player? {
        val masterUuid = this.girl.getEntityData().get(BaseGirlEntity.MASTER)
        if (masterUuid.isEmpty()) {
            return null
        }

        return try {
            this.girl.level().getPlayerByUUID(UUID.fromString(masterUuid))
        } catch (exception: IllegalArgumentException) {
            null
        }
    }

    override fun tick() {
        this.state = this.getCurrentState()
        val watch = this.girl.watchClosestGirlGoal
        if (watch != null) {
            watch.isWatching = this.state == GirlFollowAiBaseState.IDLE
        }

        this.setState(this.state)
    }

    abstract fun getCurrentState(): GirlFollowAiBaseState

    abstract fun setState(state: GirlFollowAiBaseState)

    @SubscribeEvent
    open fun onLivingDeath(event: LivingDeathEvent) {
        val entity = event.entity
        if (entity is BaseGirlEntity) {
            if (entity.getEntityData().get(BaseGirlEntity.MASTER) != "") {
                event.isCanceled = true
            }
        }
    }

    enum class GirlFollowAiBaseState {
        ATTACK,
        FOLLOW,
        IDLE,
        RIDE,
        DOWNED,
    }

    companion object {
        const val followDistance: Double = 0.5
        const val attackDistance: Double = 0.7
        const val updateTicks: Int = 60
    }
}
