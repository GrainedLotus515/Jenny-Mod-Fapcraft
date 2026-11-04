package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.GalathEntity
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

/**
 * DamageSource for galath attacks.
 *
 * Port notes: 1.21 damage sources carry a data-driven [DamageType]
 * (`data/sexmod/damage_type/galath.json`, message id "galath" like the 1.12
 * `super("galath")`). `getImmediateSource()`/`getTrueSource()` map to the
 * direct/causing entity passed to the super constructor (both Galath),
 * `getDamageLocation()` to [getSourcePosition] and `getDeathMessage` to
 * [getLocalizedDeathMessage].
 */
open class GalathDamageSource(galath: GalathEntity) :
    DamageSource(damageType(galath), galath, galath, galath.position()) {
    @JvmField
    var galathEntity: GalathEntity = galath

    @JvmField
    var sourcePos: Vec3 = galath.position()

    override fun getLocalizedDeathMessage(livingEntity: LivingEntity): Component {
        return Component.literal(livingEntity.name.string + " was slain by Galath")
    }

    override fun getSourcePosition(): Vec3 = this.sourcePos

    companion object {
        @JvmField
        val GALATH: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, Main.rl("galath"))

        @JvmStatic
        fun damageType(galath: GalathEntity): Holder<DamageType> =
            galath.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(GALATH)
    }
}
