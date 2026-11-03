package com.trolmastercard.sexmod.entity.api

import com.trolmastercard.sexmod.entity.Action
import java.util.UUID

/**
 * <b>Role.</b> The goblin carry/throw contract shared by `GoblinEntity`
 * and `GoblinPlayerEntity`: owner binding, the throw flight state
 * (`throwProgress` -1 idle, 0..39 in flight, `throwTickCount`
 * grounded roll ticks, `heldPlayerDistance` pickup countdown) and the
 * previous-action diff used to detect a fresh throw. The shared static
 * helpers in `GoblinEntity` (e.g. `handlePickUpState`,
 * `handleGoblinThrowAction`, `getGoblinThrowPos`) operate on this
 * interface — do not remove any member.
 */
interface IGoblin {
    fun getOwnerUUID(): UUID?

    fun setOwnerUUID(ownerUuid: UUID?)

    fun getHeldPlayerDistance(): Int

    fun setThrowProgress(throwProgress: Int)

    fun getThrowProgress(): Int

    fun setThrowTickCount(throwTickCount: Int)

    fun getThrowTickCount(): Int

    fun setPreviousAction(previousAction: Action?)

    fun getPreviousAction(): Action?

    fun setHeldPlayerDistance(heldPlayerDistance: Int)
}
