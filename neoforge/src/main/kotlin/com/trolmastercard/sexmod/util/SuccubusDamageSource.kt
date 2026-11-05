package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.GalathEntity
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.DamageTypeTags
import net.minecraft.tags.TagKey
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3

/**
 * DamageSource for succubus-style drain attacks.
 *
 * Port notes: 1.21 damage sources carry a data-driven [DamageType]
 * (`data/sexmod/damage_type/succubus.json`, message id "galath" like the 1.12
 * `super("galath")`). The 1.12 overrides map as follows: `isUnblockable()` and
 * `canHarmInCreative()` -> [is] answers true for `#bypasses_armor` and
 * `#bypasses_invulnerability`; `getImmediateSource()`/`getTrueSource()` -> the
 * direct/causing entity passed to the super constructor; `getDamageLocation()`
 * -> the source position; `getDeathMessage` -> [getLocalizedDeathMessage].
 */
open class SuccubusDamageSource(galath: GalathEntity) :
    DamageSource(damageType(galath), galath, galath, galath.position()) {
    @JvmField
    var galathEntity: GalathEntity = galath

    @JvmField
    var sourcePos: Vec3 = galath.position()

    override fun getLocalizedDeathMessage(livingEntity: LivingEntity): Component {
        return Component.literal(livingEntity.name.string + " got his cum drained by a Succubus")
    }

    override fun `is`(damageTypeKey: TagKey<DamageType>): Boolean {
        // 1.12 isUnblockable() = true, canHarmInCreative() = true
        if (damageTypeKey == DamageTypeTags.BYPASSES_ARMOR || damageTypeKey == DamageTypeTags.BYPASSES_INVULNERABILITY) {
            return true
        }

        return super.`is`(damageTypeKey)
    }

    override fun getSourcePosition(): Vec3 = this.sourcePos

    companion object {
        @JvmField
        val SUCCUBUS: ResourceKey<DamageType> = ResourceKey.create(Registries.DAMAGE_TYPE, Main.rl("succubus"))

        @JvmStatic
        fun damageType(galath: GalathEntity): Holder<DamageType> =
            galath.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(SUCCUBUS)
    }
}
