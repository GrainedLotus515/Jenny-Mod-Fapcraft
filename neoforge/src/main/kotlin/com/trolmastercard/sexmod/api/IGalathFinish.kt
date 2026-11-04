package com.trolmastercard.sexmod.api

import com.trolmastercard.sexmod.entity.GalathEntity

/**
 * Callback fired when a galath scene/fight finishes.
 */
fun interface IGalathFinish {
    fun finish(galath: GalathEntity)
}
