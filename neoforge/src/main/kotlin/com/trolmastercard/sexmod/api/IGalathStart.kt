package com.trolmastercard.sexmod.api

import com.trolmastercard.sexmod.entity.GalathEntity

/**
 * Callback fired when a galath scene/fight starts.
 */
fun interface IGalathStart {
    fun start(galath: GalathEntity)
}
