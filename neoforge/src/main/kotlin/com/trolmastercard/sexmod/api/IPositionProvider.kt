package com.trolmastercard.sexmod.api

import com.trolmastercard.sexmod.entity.BaseGirlEntity
import net.minecraft.world.phys.Vec3

/**
 * Provides a Vec3 position (used by scene positioning helpers).
 */
fun interface IPositionProvider {
    fun getPosition(girl: BaseGirlEntity): Vec3
}
