package com.trolmastercard.sexmod.entity.ai

import java.util.EnumSet
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.AgeableMob
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.targeting.TargetingConditions
import net.minecraft.world.entity.npc.Villager
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent

/**
 * <b>Role.</b> Leftover vanilla villager-breeding AI (an unused side effect of
 * the deobf that is still registered nowhere for girls). Makes a villager
 * mate with a nearby villager, then breeds a child through the
 * [BabyEntitySpawnEvent].
 *
 * <b>Pitfalls.</b> Nothing in the mod attaches this goal — it is dead code
 * kept jar-faithful. Do not wire it to any girl.
 *
 * Port notes: 1.21 villagers breed through their Brain; the 1.12
 * `setMating` / `setIsWillingToMate` flags no longer exist (PORT-GAPs below).
 */
open class GirlAiBase(villager: Villager) : Goal() {
    private val targetVillager: Villager = villager
    private var homeVillager: Villager? = null
    private val world: Level = villager.level()
    private var tickInterval: Int = 0

    init {
        // 1.12 setMutexBits(3) = MOVE | LOOK
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK))
    }

    override fun canUse(): Boolean {
        if (this.tickInterval != 0) {
            return false
        }

        val nearest = this.world.getNearestEntity(
            Villager::class.java,
            TargetingConditions.forNonCombat(),
            this.targetVillager,
            this.targetVillager.x,
            this.targetVillager.y,
            this.targetVillager.z,
            this.targetVillager.boundingBox.inflate(8.0, 3.0, 8.0),
        ) ?: return false

        this.homeVillager = nearest
        return true
    }

    override fun start() {
        this.tickInterval = 300
        // PORT-GAP: 1.12 EntityVillager.setMating(true) has no 1.21 equivalent (brain-driven breeding)
    }

    override fun stop() {
    }

    override fun canContinueToUse(): Boolean = true

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun tick() {
        val home = this.homeVillager!! // java-npe
        this.tickInterval--
        this.targetVillager.lookControl.setLookAt(home, 10.0f, 30.0f)
        if (this.targetVillager.distanceToSqr(home) > 2.25) {
            this.targetVillager.navigation.moveTo(home, 0.25)
        }

        if (this.tickInterval <= 0) {
            this.breedVillagers()
            this.targetVillager.goalSelector.removeGoal(this)
        }

        if (this.targetVillager.random.nextInt(35) == 0) {
            this.world.broadcastEntityEvent(this.targetVillager, 12.toByte())
        }
    }

    private fun breedVillagers() {
        val home = this.homeVillager!! // java-npe
        val serverLevel = this.world as? ServerLevel ?: return
        val child: AgeableMob? = this.targetVillager.getBreedOffspring(serverLevel, home)
        home.age = 6000
        this.targetVillager.age = 6000
        // PORT-GAP: 1.12 setIsWillingToMate(false) has no 1.21 equivalent
        val spawnEvent = BabyEntitySpawnEvent(this.targetVillager, home, child)
        if (!NeoForge.EVENT_BUS.post(spawnEvent).isCanceled && spawnEvent.child != null) {
            val spawnedChild = spawnEvent.child!! // checked just above
            spawnedChild.age = -24000
            spawnedChild.moveTo(this.targetVillager.x, this.targetVillager.y, this.targetVillager.z, 0.0f, 0.0f)
            this.world.addFreshEntity(spawnedChild)
            this.world.broadcastEntityEvent(spawnedChild, 12.toByte())
        }
    }
}
