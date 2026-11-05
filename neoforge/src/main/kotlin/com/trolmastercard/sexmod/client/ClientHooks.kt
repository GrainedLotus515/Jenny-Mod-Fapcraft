package com.trolmastercard.sexmod.client

import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * CLIENT-ONLY helpers for COMMON classes (§2.3 Rule D). Every member is
 * `@JvmStatic` and uses only common types in its signature, so common code may
 * call them on client-only paths (animation predicates, sound/keyframe
 * handlers, `level().isClientSide` blocks, client-bound packet handlers)
 * without loading any `net.minecraft.client` class on a dedicated server.
 */
object ClientHooks {
    @JvmStatic
    fun localPlayer(): Player? = Minecraft.getInstance().player

    @JvmStatic
    fun localPlayerUUID(): UUID? = Minecraft.getInstance().player?.uuid

    @JvmStatic
    fun localPlayerName(): String = Minecraft.getInstance().player?.gameProfile?.name ?: ""

    @JvmStatic
    fun clientLevel(): Level? = Minecraft.getInstance().level

    @JvmStatic
    fun partialTick(): Float = Minecraft.getInstance().timer.getGameTimeDeltaPartialTick(true)

    /** 1.12 `gameSettings.thirdPersonView`: 0 = first person, 1 = third person back, 2 = third person front. */
    @JvmStatic
    fun thirdPersonView(): Int = Minecraft.getInstance().options.cameraType.ordinal

    /** Sets the camera with the 1.12 `thirdPersonView` semantics (0 = first, 1 = back, 2 = front). */
    @JvmStatic
    fun setThirdPersonView(mode: Int) {
        val types = CameraType.entries
        Minecraft.getInstance().options.cameraType = types[Math.floorMod(mode, types.size)]
    }

    @JvmStatic
    fun isJumpKeyDown(): Boolean = Minecraft.getInstance().options.keyJump.isDown

    @JvmStatic
    fun isSneakKeyDown(): Boolean = Minecraft.getInstance().options.keyShift.isDown

    @JvmStatic
    fun isScreenOpen(): Boolean = Minecraft.getInstance().screen != null

    @JvmStatic
    fun closeScreen() {
        Minecraft.getInstance().setScreen(null)
    }

    @JvmStatic
    fun sessionProfileId(): UUID = Minecraft.getInstance().user.profileId

    /** 1.12 `Minecraft.player.sendMessage(new TextComponentString(text))`: local chat line. */
    @JvmStatic
    fun displayChat(text: String) {
        Minecraft.getInstance().gui.chat.addMessage(Component.literal(text))
    }

    @JvmStatic
    fun getEntityById(id: Int): Entity? = Minecraft.getInstance().level?.getEntity(id)
}
