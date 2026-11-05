package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.GalathEntity

/**
 * Executable action hook for galath AI states.
 */
fun interface IGalathExecute {
    fun canExecute(galath: GalathEntity): Boolean
}
