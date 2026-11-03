package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.BaseGirlEntity

/**
 * Supplies a bone rotation value (render-time hook for bone animation).
 */
fun interface IBoneRotationSupplier {
    fun getRotation(girl: BaseGirlEntity): Float
}
