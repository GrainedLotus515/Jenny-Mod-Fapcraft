package com.trolmastercard.sexmod.api

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.world.phys.Vec3

/**
 * Provides a target entity (used by scene targeting helpers).
 */
fun interface ITargetProvider {
    fun getTargetPosition(girl: BaseGirlEntity): Vec3
}
