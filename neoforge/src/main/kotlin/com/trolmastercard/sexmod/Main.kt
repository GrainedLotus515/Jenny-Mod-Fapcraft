package com.trolmastercard.sexmod

import net.neoforged.fml.common.Mod
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@Mod(Main.MOD_ID)
object Main {
    const val MOD_ID = "sexmod"
    val LOGGER: Logger = LogManager.getLogger(MOD_ID)

    init {
        LOGGER.info("Fapcraft (NeoForge 1.21.1) loading")
    }
}
