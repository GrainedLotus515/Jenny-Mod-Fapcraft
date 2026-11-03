package com.trolmastercard.sexmod.entity.api

import com.trolmastercard.sexmod.util.Vector4d

/**
 * <b>Role.</b> The Galath render/shared contract, implemented by
 * `GalathEntity` and `GalathPlayerEntity`:
 * [getFlightData] feeds the flight-HUD/wing animation tilt, the
 * wing-animation flags decide whether the wing bones animate (dressed/action
 * dependent), and [isHuggingManglelie] marks the hug actions so the
 * renderer hides/positions her correctly.
 */
interface IGalath {
    fun getFlightData(): Vector4d

    fun isWingsAnimated(): Boolean

    fun areWingsAnimated(): Boolean

    fun isHuggingManglelie(): Boolean
}
