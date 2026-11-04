package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.entity.KoboldEntity
import net.minecraft.core.BlockPos

/**
 * <b>Role.</b> Read-side task lookups for [KoboldManager]: which task owns
 * a mined block, which task a kobold is working on, and handing every task of a
 * tribe its new worker.
 *
 * <b>Pitfalls.</b> The worker lookup keeps the *last* matching task, the block
 * lookup stops at the first — a kobold assigned to two tasks therefore loses the
 * earlier one. A block can legitimately appear in several tasks; only the first
 * is returned.
 */
object KoboldTaskBook {
    @JvmStatic
    fun findTaskWithTarget(tasks: Collection<KoboldTask>, pos: BlockPos?): KoboldTask? {
        var found: KoboldTask? = null

        for (task in tasks) {
            if (task.miningTargets.contains(pos)) {
                found = task
                break
            }
        }

        return found
    }

    @JvmStatic
    fun findTaskWithWorker(tasks: Collection<KoboldTask>, kobold: KoboldEntity): KoboldTask? {
        var found: KoboldTask? = null

        for (task in tasks) {
            if (task.hasWorker(kobold)) {
                found = task
            }
        }

        return found
    }

    @JvmStatic
    fun addWorkerToAll(tasks: Collection<KoboldTask>, kobold: KoboldEntity) {
        for (task in tasks) {
            task.addWorker(kobold)
        }
    }
}
