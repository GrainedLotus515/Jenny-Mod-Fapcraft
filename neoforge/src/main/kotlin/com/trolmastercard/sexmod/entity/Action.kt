package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.util.ThreadNames
import software.bernie.geckolib.animation.AnimationController

/**
 * The scene/action state machine shared by every girl (NPC and player-girl).
 *
 * A girl's current state is stored in the [BaseGirlEntity.CUR_ACTION]
 * synced-data entry as this enum's `name`. Transitions happen through
 * [BaseGirlEntity.setCurrentAction] — on the CLIENT that routes
 * through `ChangeDataParameterPacket("currentAction", ...)` to the
 * SERVER, so both sides stay in sync via the entity data manager.
 *
 * <b>Action semantics.</b> Each constant carries:
 * - [transitionTick] — geckolib controller transition length for
 *   the animation switch (frames).
 * - [hasPlayer] — whether the interaction player is part of the
 *   scene (scene actions = true, idle/AI actions = false).
 * - [autoBlink] — whether the eyes controller plays the blink
 *   animation while this action runs.
 * - [maxGirlPitch]/[minGirlPitch] — camera pitch clamp range
 *   used by the scene camera.
 * - [flipGirlYaw] — whether the camera view is flipped 180°
 *   (reverse/behind camera scenes).
 * - [useBoyCam] — whether the camera follows the player instead of
 *   the girl.
 * - [hideNameTag] — suppress the name tag while active.
 * - [length]/[followUp] — optional timed auto-transition
 *   (see below).
 *
 * <b>Progression.</b> Three advancement mechanisms:
 * 1. <b>Sound-keyframe transitions</b> (primary): the animation's
 *    `xxxDone`/`xxxMSG1..N` sound events are caught in each
 *    girl's `registerControllers` sound listener, which calls
 *    [BaseGirlEntity.setCurrentAction] to advance the scene.
 * 2. <b>Auto follow-up</b>: actions constructed with a
 *    `(..., length, followUp)` tail advance on the SERVER after
 *    `length` ticks via [BaseGirlEntity.tickFollowUpTransitions].
 * 3. <b>Input</b>: `HandlePlayerMovement` maps sneak to
 *    `triggerFastSexAction` ([BaseGirlEntity.getNextAction])
 *    and jump-with-full-meter to `triggerCumAction`
 *    ([BaseGirlEntity.getCumAction]).
 *
 * <b>Scene-end convention.</b> Every scene ends with its cum action
 * (`*CUM`) whose last sound keyframe (`xxx_cumDone`) triggers
 * [BaseGirlEntity.resetCameraAndPhysics] on the client, which sends the
 * full `ResetGirlPacket` — releasing the girl and restoring the player.
 *
 * <b>Pitfall.</b> [ticksPlaying] is a mutable per-side tick counter
 * indexed `[server, client]`; `setCurrentAction` zeroes the
 * previous action's counter. Do not reuse [length]/[followUp]
 * constructors for actions that are sound-driven — a non-null followUp would
 * override the sound-listener transition after `length` ticks.
 */
enum class Action {
    NULL(0, false, true),
    STARTBLOWJOB(2, true, false),
    SUCKBLOWJOB(2, true, false),
    SUCKBLOWJOB_BLINK(2, true, true),
    CUMBLOWJOB(0, true, false),
    THRUSTBLOWJOB(2, true, false),
    PAYMENT(5, true, false),
    STARTDOGGY(2, false, false),
    WAITDOGGY(0, false, true),
    DOGGYSTART(0, true, false),
    DOGGYSLOW(2, true, false),
    DOGGYFAST(2, true, false),
    DOGGYCUM(2, true, false),
    STRIP(5, false, false),
    DASH(2, false, false),
    HUG(2, true, false),
    HUGIDLE(0, true, true),
    HUGSELECTED(0, true, false),
    UNDRESS(2, false, true),
    DRESS(2, false, true),
    SITDOWN(2, false, false, 60.0f, -90.0f, true),
    SITDOWNIDLE(0, false, true, 60.0f, -60.0f, true),
    COWGIRLSTART(0, true, false, 60.0f, -60.0f, false),
    COWGIRLSLOW(10, true, false, 60.0f, -60.0f, false),
    COWGIRLFAST(10, true, false, 60.0f, -60.0f, false),
    COWGIRLCUM(2, true, false, 60.0f, -60.0f, false),
    ATTACK(0, false, true),
    BOW(2, false, true),
    RIDE(0, false, true),
    SIT(0, false, true),
    THROW_PEARL(0, false, false),
    DOWNED(7, false, true),
    PAIZURI_START(0, true, false, -56.0f, -90.0f, false, true),
    PAIZURI_IDLE(0, true, false, -56.0f, -90.0f, false, true),
    PAIZURI_SLOW(0, true, true, -56.0f, -90.0f, false, true),
    PAIZURI_FAST(0, true, false, -56.0f, -90.0f, false, true),
    PAIZURI_FAST_CONTINUES(0, true, false, -56.0f, -90.0f, false, true),
    PAIZURI_CUM(0, true, false, -56.0f, -90.0f, false, true),
    MISSIONARY_START(0, true, false, 30.0f, -90.0f, true),
    MISSIONARY_SLOW(2, true, false, 30.0f, -90.0f, true),
    MISSIONARY_FAST(2, true, false, 30.0f, -90.0f, true),
    MISSIONARY_CUM(2, true, false, 30.0f, -90.0f, true),
    TALK_HORNY(5, true, false),
    TALK_IDLE(0, true, true),
    TALK_RESPONSE(2, true, false),
    ANAL_PREPARE(5, false, false),
    ANAL_WAIT(0, false, true),
    ANAL_START(0, true, false),
    ANAL_SLOW(2, true, true),
    ANAL_FAST(0, true, false),
    ANAL_CUM(2, true, false),
    KOBOLD_ANAL_START(0, true, false, false, 4.0f, -80.0f, true),
    KOBOLD_ANAL_SLOW(0, true, true, false, 4.0f, -80.0f, true),
    KOBOLD_ANAL_FAST(0, true, false, false, 4.0f, -80.0f, true),
    KOBOLD_ANAL_CUM(2, true, false, false, 4.0f, -80.0f, true),
    SUMMON(0, false, false, false, true),
    SUMMON_WAIT(0, false, true, false, true),
    HEAD_PAT(0, true, false),
    ALLIE_PREPARE_FIRST_TIME(0, false, false, 40.0f, -40.0f, false),
    DEEPTHROAT_START(0, true, false, true, 40.0f, -40.0f, false),
    DEEPTHROAT_SLOW(2, true, false, true, 40.0f, -40.0f, false),
    DEEPTHROAT_FAST(2, true, false, true, 40.0f, -40.0f, false),
    DEEPTHROAT_CUM(2, true, false, true, 40.0f, -40.0f, false),
    ALLIE_PREPARE_NORMAL(2, false, false, 40.0f, -40.0f, false),
    SUMMON_NORMAL(0, false, false),
    SUMMON_SAND(0, false, false),
    SUMMON_NORMAL_WAIT(2, false, true),
    RICH_FIRST_TIME(0, false, false),
    RICH_NORMAL(0, false, false),
    CITIZEN_START(0, true, false, 10.0f, -90.0f, false),
    CITIZEN_SLOW(0, true, false, 10.0f, -90.0f, false),
    CITIZEN_FAST(0, true, false, 10.0f, -90.0f, false),
    CITIZEN_CUM(2, true, false, 10.0f, -90.0f, false),
    FISHING_START(5, false, false),
    FISHING_IDLE(0, false, true),
    FISHING_EAT(0, false, false),
    FISHING_THROW_AWAY(0, false, false),
    TOUCH_BOOBS_INTRO(0, true, false),
    TOUCH_BOOBS_SLOW(2, true, false),
    TOUCH_BOOBS_FAST(2, true, false),
    TOUCH_BOOBS_CUM(2, true, false),
    WAIT_CAT(0, false, false, 30.0f, -90.0f, true),
    COWGIRL_SITTING_INTRO(0, true, false),
    COWGIRL_SITTING_SLOW(5, true, false),
    COWGIRL_SITTING_FAST(5, true, false),
    COWGIRL_SITTING_CUM(5, true, false),
    MINE(0, false, false),
    SLEEP(5, false, false),
    MATING_PRESS_START(0, true, false, false, -50.0f, -90.0f, false),
    MATING_PRESS_SOFT(0, true, false, -50.0f, -90.0f, false),
    MATING_PRESS_HARD(0, true, false, -50.0f, -90.0f, false),
    MATING_PRESS_CUM(2, true, false, -30.0f, -90.0f, false),
    SHOULDER_IDLE(0, false, true, false, true),
    PICK_UP(0, true, false, 10.0f, -90.0f, true, true),
    RUN(5, false, true),
    CATCH(0, true, false),
    CATCH_BJ(0, true, false),
    CATCH_BJ_IDLE(0, true, false),
    START_THROWING(0, true, true),
    THROWN(0, false, true),
    JUMP_0(0, true, false),
    JUMP_1(0, false, false),
    JUMP_2(0, false, false),
    BREEDING_INTRO_0(0, true, false),
    BREEDING_INTRO_1(0, false, false),
    BREEDING_INTRO_2(0, false, false),
    BREEDING_SLOW_0(0, true, false),
    BREEDING_1(0, false, false),
    BREEDING_SLOW_2(5, false, false),
    BREEDING_FAST_0(0, true, false),
    BREEDING_FAST_2(5, false, false),
    BREEDING_CUM_0(0, true, false),
    BREEDING_CUM_1(0, false, false),
    BREEDING_CUM_2(0, false, false),
    AWAIT_PICK_UP(0, false, true),
    VANISH(0, false, true),
    STAND_UP(0, false, false),
    NELSON_INTRO(0, true, false, 30.0f, -20.0f, false, true),
    NELSON_SLOW(0, true, false, 30.0f, -20.0f, false, true),
    NELSON_FAST(0, true, false, 30.0f, -20.0f, false, true),
    NELSON_CUM(0, true, false, 30.0f, -20.0f, false, true),
    CARRY_SLOW(0, true, false, true, true),
    CARRY_FAST(0, true, false, true, true),
    CARRY_CUM(0, true, false, true, true),
    CARRY_INTRO(0, true, false, true, true, 191, CARRY_SLOW),
    PRONE_DOGGY_INTRO(0, true, false, true, true),
    PRONE_DOGGY_SOFT(0, true, false, true, true),
    PRONE_DOGGY_HARD(0, true, false, true, true, 34, PRONE_DOGGY_SOFT),
    PRONE_DOGGY_INSERT(2, true, false, true, true, 42, PRONE_DOGGY_SOFT),
    PRONE_DOGGY_CUM(0, true, false, true, true),
    REVERSE_COWGIRL_SLOW(0, true, false, true, 30.0f, -90.0f, true),
    REVERSE_COWGIRL_FAST_START(0, true, false, true, 34, REVERSE_COWGIRL_SLOW, 30.0f, -90.0f, true),
    REVERSE_COWGIRL_FAST_CONTINUES(0, true, false, true, 39, REVERSE_COWGIRL_SLOW, 30.0f, -90.0f, true),
    REVERSE_COWGIRL_CUM(0, true, false, true, 30.0f, -90.0f, true),
    REVERSE_COWGIRL_START(0, true, false, true, 88, REVERSE_COWGIRL_SLOW, 30.0f, -90.0f, true),
    WAVE_IDLE(0, false, false, false, true),
    WAVE(0, false, false, true, false, 71, WAVE_IDLE),
    FLY(0, false, true),
    SUMMON_SKELETON(0, false, false),
    ATTACK_SWORD(0, false, false),
    KNOCK_OUT_FLY(5, false, false),
    KNOCK_OUT_GROUND(3, false, false),
    KNOCK_OUT_STAND_UP(0, false, false),
    RAPE_PREPARE(0, false, false),
    RAPE_CHARGE(0, false, false),
    RAPE_ON_GOING(0, true, false, true, 60.0f, -30.0f, false),
    RAPE_INTRO(0, true, false, false, true, 46, RAPE_ON_GOING),
    RAPE_CUM_IDLE(0, true, false, true),
    RAPE_CUM(0, true, false, true, 34, RAPE_CUM_IDLE, 60.0f, -30.0f, false),
    CORRUPT_SLOW(0, true, false, -30.0f, -90.0f, false),
    CORRUPT_FAST(0, true, false, -30.0f, -90.0f, false),
    CORRUPT_CUM(0, true, false, false, -30.0f, -90.0f, false),
    CORRUPT_INTRO(0, true, false, true, 29, CORRUPT_SLOW),
    CONTROLLED_FLIGHT(0, true, true, true, true),
    BOOST(3, true, false, true, true, 43, CONTROLLED_FLIGHT),
    GALATH_SUMMON(0, false, false, false, true, 15, NULL),
    GALATH_DE_SUMMON(0, false, false, false, true),
    GIVE_COIN(0, true, false, true, true, 140, NULL),
    MASTERBATE(0, false, false),
    HUG_MANG(0, false, false, 239, NULL),
    RIDE_MOMMY_HEAD(0, false, true),
    THREESOME_SLOW(0, true, false, false, true),
    THREESOME_FAST(0, true, false, false, true),
    THREESOME_CUM(0, true, false, false, true),
    PUSSY_LICKING(0, false, true, false),
    MASTERBATE_SITTING(0, false, true, false),
    MASTERBATE_SITTING_CUM(0, false, false, false),
    MORNING_BLOWJOB_SLOW(0, true, true, true),
    MORNING_BLOWJOB_FAST(0, true, true, true),
    MORNING_BLOWJOB_CUM(0, true, false, true);

    /** Geckolib transition length (frames) when switching into this action. */
    @JvmField
    val transitionTick: Int

    /** Whether the interaction player participates in this action (scene action). */
    @JvmField
    val hasPlayer: Boolean

    /** Whether the eyes controller plays the blink/idle animation during this action. */
    @JvmField
    val autoBlink: Boolean

    /** Maximum camera pitch allowed while this action runs. */
    @JvmField
    val maxGirlPitch: Float

    /** Minimum camera pitch allowed while this action runs. */
    @JvmField
    val minGirlPitch: Float

    /** Whether the scene camera is flipped 180 degrees (reverse camera scenes). */
    @JvmField
    val flipGirlYaw: Boolean

    /**
     * Ticks this action plays before the auto follow-up fires (SERVER side).
     * Only set for actions constructed with a followUp tail; 0 for
     * sound-driven actions (a followUp would then fire on the first tick).
     */
    @JvmField
    var length: Int = 0

    /**
     * Ticks the action has been playing, per side: index 0 = SERVER,
     * index 1 = CLIENT. Reset to `{0,0}` by
     * [BaseGirlEntity.setCurrentAction] when the girl leaves the action.
     */
    @JvmField
    var ticksPlaying: IntArray = intArrayOf(0, 0)

    /** Auto-transition target (SERVER side) after [length] ticks, or `null`. */
    @JvmField
    var followUp: Action? = null

    /** Whether the scene camera follows the player (boy camera) instead of the girl. */
    @JvmField
    var useBoyCam: Boolean = false

    /** Whether the name tag is hidden while this action is active. */
    @JvmField
    var hideNameTag: Boolean = false

    constructor(transitionTick: Int, hasPlayer: Boolean, autoBlink: Boolean) {
        this.transitionTick = transitionTick
        this.hasPlayer = hasPlayer
        this.autoBlink = autoBlink
        this.maxGirlPitch = 30.0f
        this.minGirlPitch = -90.0f
        this.flipGirlYaw = false
        this.useBoyCam = false
        this.hideNameTag = false
    }

    constructor(transitionTick: Int, hasPlayer: Boolean, autoBlink: Boolean, useBoyCam: Boolean) :
        this(transitionTick, hasPlayer, autoBlink) {
        this.useBoyCam = useBoyCam
    }

    constructor(transitionTick: Int, hasPlayer: Boolean, autoBlink: Boolean, useBoyCam: Boolean, hideNameTag: Boolean) :
        this(transitionTick, hasPlayer, autoBlink) {
        this.useBoyCam = useBoyCam
        this.hideNameTag = hideNameTag
    }

    constructor(
        transitionTick: Int,
        hasPlayer: Boolean,
        autoBlink: Boolean,
        maxGirlPitch: Float,
        minGirlPitch: Float,
        flipGirlYaw: Boolean,
    ) {
        this.transitionTick = transitionTick
        this.hasPlayer = hasPlayer
        this.autoBlink = autoBlink
        this.maxGirlPitch = maxGirlPitch
        this.minGirlPitch = minGirlPitch
        this.flipGirlYaw = flipGirlYaw
        this.useBoyCam = false
        this.hideNameTag = false
    }

    constructor(
        transitionTick: Int,
        hasPlayer: Boolean,
        autoBlink: Boolean,
        maxGirlPitch: Float,
        minGirlPitch: Float,
        flipGirlYaw: Boolean,
        hideNameTag: Boolean,
    ) {
        this.transitionTick = transitionTick
        this.hasPlayer = hasPlayer
        this.autoBlink = autoBlink
        this.maxGirlPitch = maxGirlPitch
        this.minGirlPitch = minGirlPitch
        this.flipGirlYaw = flipGirlYaw
        this.useBoyCam = false
        this.hideNameTag = hideNameTag
    }

    constructor(
        transitionTick: Int,
        hasPlayer: Boolean,
        autoBlink: Boolean,
        useBoyCam: Boolean,
        maxGirlPitch: Float,
        minGirlPitch: Float,
        flipGirlYaw: Boolean,
    ) {
        this.transitionTick = transitionTick
        this.hasPlayer = hasPlayer
        this.autoBlink = autoBlink
        this.maxGirlPitch = maxGirlPitch
        this.minGirlPitch = minGirlPitch
        this.flipGirlYaw = flipGirlYaw
        this.hideNameTag = false
        this.useBoyCam = useBoyCam
    }

    constructor(transitionTick: Int, hasPlayer: Boolean, autoBlink: Boolean, length: Int, followUp: Action?) :
        this(transitionTick, hasPlayer, autoBlink) {
        this.length = length
        this.followUp = followUp
    }

    constructor(
        transitionTick: Int,
        hasPlayer: Boolean,
        autoBlink: Boolean,
        useBoyCam: Boolean,
        length: Int,
        followUp: Action?,
    ) : this(transitionTick, hasPlayer, autoBlink) {
        this.length = length
        this.followUp = followUp
        this.useBoyCam = useBoyCam
    }

    constructor(
        transitionTick: Int,
        hasPlayer: Boolean,
        autoBlink: Boolean,
        useBoyCam: Boolean,
        length: Int,
        followUp: Action?,
        minGirlPitch: Float,
        maxGirlPitch: Float,
        flipGirlYaw: Boolean,
    ) {
        this.transitionTick = transitionTick
        this.hasPlayer = hasPlayer
        this.autoBlink = autoBlink
        this.length = length
        this.followUp = followUp
        this.useBoyCam = useBoyCam
        this.minGirlPitch = minGirlPitch
        this.maxGirlPitch = maxGirlPitch
        this.flipGirlYaw = flipGirlYaw
    }

    constructor(
        transitionTick: Int,
        hasPlayer: Boolean,
        autoBlink: Boolean,
        hideNameTag: Boolean,
        useBoyCam: Boolean,
        length: Int,
        followUp: Action?,
    ) : this(transitionTick, hasPlayer, autoBlink) {
        this.length = length
        this.followUp = followUp
        this.useBoyCam = useBoyCam
        this.hideNameTag = hideNameTag
    }

    companion object {
        @JvmStatic
        fun isAny(candidate: Action?, vararg actions: Action?): Boolean {
            for (action in actions) {
                if (candidate === action) {
                    return true
                }
            }

            return false
        }

        @JvmStatic
        fun isAnyAction(girl: BaseGirlEntity, vararg actions: Action?): Boolean {
            return isAny(girl.getCurrentAction(), *actions)
        }

        /** Length (ticks) of the controller's current animation, 0 if none (GL3 `animationLength`). */
        @JvmStatic
        fun getActionProgress(controller: AnimationController<*>?): Double {
            if (controller == null) {
                return 0.0
            }

            val animation = controller.getCurrentAnimation()
            return animation?.animation()?.length() ?: 0.0
        }

        /** CLIENT. */
        @JvmStatic
        fun getActionLength(girl: BaseGirlEntity): Float {
            return getActionProgress(girl.actionController).toFloat()
        }

        /**
         * CLIENT: ticks the action controller has been playing its current
         * animation. GL3 read `AnimationData.tick`; GL4's equivalent is the
         * seek time the controller captured in `GirlAnimationController.process`.
         * Returns 0 while the controller does not exist (server / not yet rendered).
         */
        @JvmStatic
        fun getActionTick(girl: BaseGirlEntity, offset: Float): Float {
            val controller = girl.actionController ?: return 0.0f
            return (controller.lastSeekTime + offset - controller.getTickOffset()).toFloat()
        }

        /** CLIENT. */
        @JvmStatic
        fun getActionTickSeconds(girl: BaseGirlEntity, offset: Float): Float {
            return getActionTick(girl, offset) / 20.0f
        }

        /** CLIENT. */
        @JvmStatic
        fun getActionTimeScale(girl: BaseGirlEntity, offset: Float): Float {
            val length = getActionLength(girl)
            return if (length <= 0.0f) {
                0.0f
            } else {
                ThreadNames.clampDouble((getActionTick(girl, offset) / length).toDouble(), 0.0, 1.0).toFloat()
            }
        }

        /** CLIENT. */
        @JvmStatic
        fun isActionComplete(girl: BaseGirlEntity, offset: Float): Boolean {
            return getActionTimeScale(girl, offset) == 1.0f
        }
    }
}
