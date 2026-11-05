package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.GalathEntity

/**
 * Per-tick update hook for galath AI states.
 */
fun interface IGalathUpdate {
    fun update(galath: GalathEntity): Boolean
}
