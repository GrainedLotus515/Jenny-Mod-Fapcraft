package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.reskin.UpdateChecker
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * **Role.** The once-per-session update check behind
 * [ServerWhitelistManager.checkForUpdatesOnce]: asks
 * [UpdateChecker] for the latest GitHub release on a background thread
 * and posts one chat notice when this build is behind.
 *
 * **Pitfalls.** The run flag is set before the thread starts, so a failed or
 * offline check is never retried this session. Everything on the thread is
 * wrapped in a catch-all — an update check must never crash the game — and the
 * chat message is only sent from the client thread via `Minecraft.execute`.
 *
 * CLIENT-ONLY.
 */
object UpdateNotice {
    /** Whether the update check already ran this session (never pester per join). */
    @Volatile
    private var updateCheckRanThisSession = false

    @JvmStatic
    fun checkOnce() {
        if (updateCheckRanThisSession) return
        updateCheckRanThisSession = true
        val t = Thread({
            try {
                val latest = UpdateChecker.fetchLatestTag() ?: return@Thread // offline / no release — silent
                val localBuild = ServerWhitelistManager.localBuild()
                val outdated = UpdateChecker.isOutdatedBuild(localBuild, latest)
                if (outdated) {
                    if (!UpdateChecker.alreadyNotified("build$localBuild", latest)) {
                        UpdateChecker.markNotified("build$localBuild", latest)
                        val body = UpdateChecker.fetchLatestBody()
                        val change = UpdateChecker.summarizeBody(body, 5)
                        val notice = ModelNotifications.buildOutdatedNotice(localBuild, latest, change)
                        Minecraft.getInstance().execute {
                            Minecraft.getInstance().player?.sendSystemMessage(Component.literal(notice))
                        }
                    }
                }
            } catch (ex: Throwable) {
                // silent — update check must never crash the game
            }
        }, "sexmod-update-check")
        t.isDaemon = true
        t.start()
    }
}
