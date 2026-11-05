package com.trolmastercard.sexmod.entity.ai

import com.trolmastercard.sexmod.entity.AbstractGirlNpcEntity
import com.trolmastercard.sexmod.entity.Action
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.util.Reference
import com.trolmastercard.sexmod.util.VectorMath
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Creeper
import net.minecraft.world.entity.monster.Monster
import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraft.world.entity.projectile.Arrow
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.living.LivingHealEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent

/**
 * <b>Role.</b> The combat follow goal for the NPC girls
 * ([AbstractGirlNpcEntity]) — the girl's full companion behaviour:
 * follows the master, rides the master's mount, attacks hostile mobs and
 * anything that attacked the girl or her master (melee with her weapon, or a
 * charged bow shot from the bow slot), wanders near the master when idle and
 * enters the downed state at 0 HP.
 *
 * <b>Flow.</b> [getCurrentState] evaluates the world each tick
 * (ride -> target -> attacker -> master's target -> nearby mobs ->
 * follow/idle); [setState] executes the chosen state.
 * Combat data is mirrored into `ATTACK_MODE` (0 idle, 1 melee, 2 bow)
 * and the weapon damage is computed from the held item's attribute modifiers
 * ([startAttack]).
 *
 * <b>Pitfalls.</b> The nested [DownedGirlEvents] owns the downed mechanic:
 * damage that would kill a bound girl instead downs her (action DOWNED, 1 HP
 * remaining), healing to full revives her, and death drops the inventory.
 * [tick] force-clears the BOW action each tick.
 *
 * Port notes: enchantments are data-driven in 1.21 (levels are read through
 * the registry holders); the creature-type damage bonus is
 * `EnchantmentHelper.modifyDamage(level, weapon, target, source, 0)`; the Punch
 * knockback field no longer exists on arrows (PORT-GAP below).
 */
open class GirlFollowGoal(girl: AbstractGirlNpcEntity) : GirlFollowAiBase(girl) {
    override val girl: AbstractGirlNpcEntity = girl

    @JvmField
    var target: LivingEntity? = null

    @JvmField
    var mountEntity: Entity? = null

    @JvmField
    var lastDistance: Double = Float.MAX_VALUE.toDouble()

    @JvmField
    var masterPos: Vec3 = Vec3.ZERO

    @JvmField
    var wanderTimer: Int = 0

    @JvmField
    var attackCooldown: Int = 0

    @JvmField
    var attackTimer: Int = 0

    @JvmField
    var bowTimer: Int = 0

    @JvmField
    var checkTimer: Int = 0

    override fun tick() {
        super.tick()
        val master = this.master!! // java-npe
        this.lastDistance = this.girl.distanceTo(master).toDouble()
        this.masterPos = master.position()
        if (this.girl.getCurrentAction() === Action.BOW) {
            this.girl.setCurrentAction(Action.NULL)
        }
    }

    open fun isTargetVisible(target: LivingEntity?): Boolean {
        val girlPos = this.girl.position()
        return target !is BaseGirlEntity &&
            this.attackCooldown <= 0 &&
            target != null &&
            target.level() != null &&
            this.girl != target &&
            target.isAlive &&
            girlPos.distanceTo(this.master!!.position()) < 15.0 &&
            girlPos.distanceTo(target.position()) < 20.0 &&
            target != this.master
    }

    /**
     * ATTACK/FOLLOW/IDLE/RIDE/DOWNED execution: melee lunge + swing, bow
     * charge + shot (32-tick cycle), follow/navigate, wander, ride
     * positioning on the master's mount, or stop.
     */
    override fun setState(state: GirlFollowAiBaseState) {
        val master = this.master!! // java-npe
        val navigator = this.navigator!! // java-npe
        val dataManager = this.dataManager!! // java-npe
        when (state) {
            GirlFollowAiBaseState.ATTACK -> {
                val target = this.target!! // java-npe
                this.girl.lookControl.setLookAt(target, 30.0f, 30.0f)
                val dist = this.girl.distanceTo(target).toDouble()
                navigator.stop()
                if (dist < 1.9 && --this.attackTimer <= 0) {
                    this.startAttack()
                } else {
                    if (this.girl.inventory.getStackInSlot(1).item is BowItem &&
                        this.girl.sensing.hasLineOfSight(target) &&
                        ++this.bowTimer > 0 &&
                        dist > 6.0
                    ) {
                        dataManager.set(AbstractGirlNpcEntity.ATTACK_MODE, 2)
                        this.girl.setCurrentAction(Action.BOW)
                        if (this.bowTimer >= 32) {
                            this.bowTimer = -20
                            this.shootArrow()
                            this.girl.setCurrentAction(Action.NULL)
                        }

                        this.lastDistance = this.girl.distanceTo(master).toDouble()
                        this.masterPos = master.position()
                        return
                    }

                    if (dist < 2.0) {
                        dataManager.set(AbstractGirlNpcEntity.ATTACK_MODE, 1)
                        navigator.moveTo(target, 0.5)
                        this.girl.setWalkSpeed(BaseGirlEntity.BaseGirlEntityState.WALK)
                    } else {
                        dataManager.set(AbstractGirlNpcEntity.ATTACK_MODE, 1)
                        navigator.moveTo(target, 0.7)
                        this.girl.setWalkSpeed(BaseGirlEntity.BaseGirlEntityState.RUN)
                    }
                }
            }
            GirlFollowAiBaseState.FOLLOW -> {
                dataManager.set(AbstractGirlNpcEntity.ATTACK_MODE, 0)
                val followDist = this.girl.distanceTo(master).toDouble()
                // 1.12 PathNavigate.getPathSearchRange() = (float) FOLLOW_RANGE
                if (this.girl.getAttributeValue(Attributes.FOLLOW_RANGE).toFloat() > followDist) {
                    navigator.stop()
                    if (!this.girl.downed) {
                        navigator.moveTo(master, 0.5)
                        this.handleKnockback()
                    }
                } else {
                    this.updateNavigation()
                }

                this.wanderTimer = 300
                this.getFollowDistance()
            }
            GirlFollowAiBaseState.IDLE -> {
                dataManager.set(AbstractGirlNpcEntity.ATTACK_MODE, 0)
                if (!this.girl.downed) {
                    if (++this.wanderTimer > 200 + Reference.RANDOM.nextInt(100)) {
                        this.wanderTimer = 0
                        val masterPos = master.position()
                        val targetPos = Vec3(
                            masterPos.x + 1.0 + Reference.RANDOM.nextFloat() * 3.0f,
                            masterPos.y,
                            masterPos.z + 1.0 + Reference.RANDOM.nextFloat() * 3.0f,
                        )
                        navigator.stop()
                        navigator.moveTo(targetPos.x, targetPos.y, targetPos.z, 0.5)
                    }

                    this.getFollowDistance()
                } else if (this.girl.distanceTo(master) > 10.0f) {
                    this.updateNavigation()
                }
            }
            GirlFollowAiBaseState.RIDE -> {
                if (this.girl.isPassenger) {
                    this.girl.setCurrentAction(Action.SIT)
                } else {
                    val mount = this.mountEntity!! // java-npe
                    this.girl.setNoGravity(true)
                    this.girl.noPhysics = true
                    val ridePos = master.position().subtract(mount.lookAngle.x * 0.5, 0.0, mount.lookAngle.z * 0.5)
                    this.girl.absMoveTo(ridePos.x, ridePos.y, ridePos.z, 0.0f, 0.0f)
                    this.girl.setDeltaMovement(0.0, 0.0, 0.0)
                    this.girl.setCurrentAction(Action.RIDE)
                }
            }
            GirlFollowAiBaseState.DOWNED -> navigator.stop()
        }
    }

    override fun getCurrentState(): GirlFollowAiBaseState {
        val master = this.master!! // java-npe
        this.attackCooldown--
        if (!this.girl.downed && this.girl.getInteractionPlayerUUID() == null) {
            if (master.isPassenger) {
                val mount = master.vehicle
                if (this.girl.isPassenger || (mount != null && this.girl.startRiding(mount))) {
                    this.mountEntity = mount
                    return GirlFollowAiBaseState.RIDE
                }
            } else if (!master.isPassenger && this.girl.isPassenger || this.state == GirlFollowAiBaseState.RIDE && !master.isPassenger) {
                this.girl.setCurrentAction(Action.NULL)
                this.girl.stopRiding()
                this.girl.noPhysics = false
                this.girl.setNoGravity(false)
            }

            if (this.isTargetVisible(this.target)) {
                return GirlFollowAiBaseState.ATTACK
            }

            var source = this.girl.lastDamageSource
            if (source != null) {
                // 1.12 cast `(EntityLivingBase) source.getTrueSource()`; a non-living source now just doesn't match
                val attacker = source.entity as? LivingEntity
                if (this.isTargetVisible(attacker)) {
                    this.target = attacker
                    return GirlFollowAiBaseState.ATTACK
                }
            }

            var attacked: LivingEntity? = master.lastHurtMob
            if (master.tickCount - master.lastHurtMobTimestamp < 140 && this.isTargetVisible(attacked)) {
                this.target = attacked
                return GirlFollowAiBaseState.ATTACK
            }

            if (this.state != GirlFollowAiBaseState.FOLLOW) {
                source = master.lastDamageSource
                if (source != null) {
                    attacked = source.entity as? LivingEntity
                    if (this.isTargetVisible(attacked)) {
                        this.target = attacked
                        return GirlFollowAiBaseState.ATTACK
                    }
                }

                val pos = this.girl.position()
                val aabb = AABB(
                    pos.x - 5.0,
                    pos.y - 2.0,
                    pos.z - 5.0,
                    pos.x + 5.0,
                    pos.y + 2.0,
                    pos.z + 5.0,
                )
                val mobs: List<Monster> = this.girl.level().getEntitiesOfClass(Monster::class.java, aabb)
                var nearestMob: Monster? = null
                var nearestDistSq = Double.MAX_VALUE

                for (mob in mobs) {
                    if (mob is Creeper) {
                        continue
                    }

                    val distSq = mob.distanceToSqr(this.girl)
                    if (distSq < nearestDistSq && this.isTargetVisible(mob)) {
                        nearestDistSq = distSq
                        nearestMob = mob
                    }
                }

                if (nearestMob != null) {
                    this.target = nearestMob
                    return GirlFollowAiBaseState.ATTACK
                }
            }

            val followDist = this.girl.distanceTo(master)
            var tooFar = followDist > 5.0f
            if (!tooFar && this.state == GirlFollowAiBaseState.FOLLOW) {
                if (++this.checkTimer > 60) {
                    tooFar = false
                    this.checkTimer = 0
                } else {
                    tooFar = true
                }
            }

            if (tooFar && this.state == GirlFollowAiBaseState.ATTACK) {
                this.attackCooldown = 60
            }

            return if (tooFar) GirlFollowAiBaseState.FOLLOW else GirlFollowAiBaseState.IDLE
        } else {
            return GirlFollowAiBaseState.DOWNED
        }
    }

    /**
     * SERVER: fires the arrow at the current target with drop compensation,
     * skeleton-style shooting sound and 4.5 base damage.
     */
    open fun shootArrow() {
        val target = this.target!! // java-npe
        val arrow = this.createArrow()
        val dx = target.x - this.girl.x
        val dy = target.boundingBox.minY + target.bbHeight / 3.0f - arrow.y
        val dz = target.z - this.girl.z
        val followDist = Math.sqrt(dx * dx + dz * dz).toFloat().toDouble()
        arrow.shoot(dx, dy + followDist * 0.2f, dz, 1.6f, 2.0f)
        this.girl.playSound(SoundEvents.SKELETON_SHOOT, 1.0f, 1.0f / (this.girl.random.nextFloat() * 0.4f + 0.8f))
        this.girl.level().addFreshEntity(arrow)
        arrow.baseDamage = 4.5
    }

    open fun createArrow(): AbstractArrow {
        val arrow = Arrow(this.girl.level(), this.girl, ItemStack(Items.ARROW), null)
        val stack = this.girl.inventory.getStackInSlot(1)
        val power = this.enchantmentLevel(Enchantments.POWER, stack).toDouble()
        val punch = this.enchantmentLevel(Enchantments.PUNCH, stack)
        val flame = this.enchantmentLevel(Enchantments.FLAME, stack)
        if (power != 0.0) {
            arrow.baseDamage = arrow.baseDamage + power * 0.5 + 0.5
        }

        if (punch != 0) {
            // PORT-GAP: 1.21 AbstractArrow has no knockback-strength field (Punch is an enchantment effect of the firing weapon)
        }

        if (flame != 0) {
            arrow.igniteForSeconds(100.0f)
        }

        return arrow
    }

    /**
     * SERVER: the melee swing — computes damage from the held weapon's
     * attack-damage/speed modifiers plus enchantments (sharpness, knockback,
     * fire aspect, sweeping), applies knockback and hits the target (and
     * sweep-adjacent enemies).
     */
    open fun startAttack() {
        val target = this.target!! // java-npe
        this.girl.setCurrentAction(Action.ATTACK)
        this.dataManager!!.set(AbstractGirlNpcEntity.ATTACK_MODE, 1) // java-npe
        val weapon = this.girl.inventory.getStackInSlot(0)
        val modifiers = weapon.attributeModifiers
        var damage = 0.0f
        var speed = 0.0f

        for (entry in modifiers.modifiers()) {
            if (entry.slot().test(EquipmentSlot.MAINHAND) && entry.attribute().`is`(Attributes.ATTACK_DAMAGE)) {
                damage = entry.modifier().amount().toFloat()
            }
        }

        for (entry in modifiers.modifiers()) {
            if (entry.slot().test(EquipmentSlot.MAINHAND) && entry.attribute().`is`(Attributes.ATTACK_SPEED)) {
                speed = entry.modifier().amount().toFloat()
            }
        }

        speed = Math.max(speed, 0.5f)
        val mobDamage = this.girl.damageSources().mobAttack(this.girl)
        val level = this.girl.level()
        // 1.12 EnchantmentHelper.getModifierForCreature: the additive enchantment bonus on top of 0 base damage
        val enchantDamage = if (level is ServerLevel) EnchantmentHelper.modifyDamage(level, weapon, target, mobDamage, 0.0f) else 0.0f
        val knockback = this.enchantmentLevel(Enchantments.KNOCKBACK, weapon)
        val fireAspect = this.enchantmentLevel(Enchantments.FIRE_ASPECT, weapon)
        val sweeping = this.enchantmentLevel(Enchantments.SWEEPING_EDGE, weapon)
        target.knockback(
            (knockback * 0.5f).toDouble(),
            Mth.sin(this.girl.yRot * (Math.PI / 180.0).toFloat()).toDouble(),
            (-Mth.cos(this.girl.yRot * (Math.PI / 180.0).toFloat())).toDouble(),
        )
        target.igniteForSeconds((fireAspect * 4).toFloat())
        if (sweeping != 0) {
            var sweepMod = 0.5f
            if (sweeping == 2) {
                sweepMod = 0.67f
            } else if (sweeping == 3) {
                sweepMod = 0.75f
            }

            for (entity in level.getEntitiesOfClass(LivingEntity::class.java, target.boundingBox.inflate(1.0, 0.25, 1.0))) {
                if (entity !== this.girl && entity !== this.master && entity !== target && !this.girl.isAlliedTo(entity) && this.girl.distanceToSqr(entity) < 9.0) {
                    entity.knockback(
                        0.4,
                        Mth.sin(this.girl.yRot * (Math.PI / 180.0).toFloat()).toDouble(),
                        (-Mth.cos(this.girl.yRot * (Math.PI / 180.0).toFloat())).toDouble(),
                    )
                    entity.hurt(this.girl.damageSources().mobAttack(this.girl), (damage + enchantDamage) * sweepMod)
                }
            }
        }

        target.hurt(mobDamage, damage + enchantDamage)
        this.attackTimer = Math.round(Math.abs(speed) / 3.373494f * 20.0f)
    }

    /** 1.12 `EnchantmentHelper.getEnchantmentLevel(Enchantment, stack)` (enchantments are registry entries in 1.21). */
    private fun enchantmentLevel(key: ResourceKey<Enchantment>, stack: ItemStack): Int {
        val holder: Holder<Enchantment> = this.girl.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolder(key).orElse(null)
            ?: return 0
        return EnchantmentHelper.getItemEnchantmentLevel(holder, stack)
    }

    override fun getFollowDistance(): Double {
        var followDistance = super.getFollowDistance()
        if (this.girl.downed) {
            followDistance = 0.0
        }

        this.navigator!!.setSpeedModifier(followDistance) // java-npe
        this.girl.setWalkSpeed(this.girl.getWalkType())
        return followDistance
    }

    override fun stop() {
        super.stop()
        this.girl.getEntityData().set(AbstractGirlNpcEntity.ATTACK_MODE, 0)
    }

    open fun handleKnockback() {
        val motion = this.girl.deltaMovement
        if (!this.girl.onGround() && !this.girl.isInWater && motion.x + motion.z == 0.0 && !(motion.y <= 0.0)) {
            var vec = Vec3(0.0, 0.0, 0.1f.toDouble())
            vec = VectorMath.rotateByYaw(vec, this.girl.yRot)
            this.girl.setDeltaMovement(vec.x, motion.y, vec.z)
        }
    }

    /**
     * Combat event handlers: lethal damage downs a bound girl instead of
     * killing her (`downed` + [Action.DOWNED]), full heal revives
     * her, and death drops her inventory (SERVER).
     *
     * Port note: 1.12 `LivingHurtEvent` is `LivingIncomingDamageEvent`.
     */
    open class DownedGirlEvents {
        @SubscribeEvent
        open fun onLivingHurt(event: LivingIncomingDamageEvent) {
            val girl = event.entity
            if (girl is AbstractGirlNpcEntity) {
                if (girl.downed) {
                    event.isCanceled = true
                } else if (girl.health - event.amount < 0.0f && girl.getEntityData().get(BaseGirlEntity.MASTER) != "") {
                    girl.downed = true
                    girl.setCurrentAction(Action.DOWNED)
                    event.amount = girl.health - 1.0f
                    girl.getNavigation().stop()
                }
            }
        }

        @SubscribeEvent
        open fun onLivingHeal(event: LivingHealEvent) {
            val girl = event.entity
            if (girl is AbstractGirlNpcEntity) {
                if (girl.downed && girl.health + event.amount >= girl.maxHealth) {
                    girl.downed = false
                    girl.setCurrentAction(Action.NULL)
                }
            }
        }

        @SubscribeEvent
        open fun onLivingDeath(event: LivingDeathEvent) {
            val girl = event.entity
            if (girl is AbstractGirlNpcEntity) {
                if (girl.level().isClientSide) {
                    return
                }

                for (i in 0 until 6) {
                    val item = girl.inventory.getStackInSlot(i).item
                    if (item !== Items.AIR) {
                        girl.spawnAtLocation(ItemStack(item, 1))
                    }
                }
            }
        }
    }
}
