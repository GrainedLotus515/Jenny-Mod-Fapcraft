package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.util.SoundHandler
import com.trolmastercard.sexmod.util.WorldUtils
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3

/**
 * Server-side sound broadcast helpers: sends a sound packet to every player
 * tracking the girl, optionally pinning it one block in front of each player.
 *
 * Extracted verbatim from [BaseGirlEntity]; the entity keeps the same
 * public static methods as delegates.
 */
object GirlSoundBroadcast {
    @JvmStatic
    fun girlPlaySound(girl: BaseGirlEntity, sound: SoundEvent, atPlayer: Boolean) {
        val pos = girl.position()

        for (player in WorldUtils.getNearbyPlayers(girl)) {
            val soundPos: Vec3
            if (!atPlayer) {
                soundPos = pos
            } else {
                val playerPos = player.position()
                val dir = pos.subtract(playerPos).normalize()
                soundPos = playerPos.add(dir)
            }

            (player as ServerPlayer)
                .connection
                .send(
                    ClientboundSoundPacket(
                        BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), SoundSource.AMBIENT,
                        soundPos.x, soundPos.y, soundPos.z, 1.0f, 1.0f, girl.random.nextLong(),
                    ),
                )
        }
    }

    @JvmStatic
    fun girlPlaySound(girl: BaseGirlEntity, sound: SoundEvent) {
        girlPlaySound(girl, sound, false)
    }

    @JvmStatic
    fun playRandomSound(girl: BaseGirlEntity, sounds: Array<SoundEvent>) {
        girlPlaySound(girl, SoundHandler.randomSound(sounds))
    }

    @JvmStatic
    fun playRandomSound(girl: BaseGirlEntity, sounds: Array<SoundEvent>, atPlayer: Boolean) {
        girlPlaySound(girl, SoundHandler.randomSound(sounds), atPlayer)
    }
}
