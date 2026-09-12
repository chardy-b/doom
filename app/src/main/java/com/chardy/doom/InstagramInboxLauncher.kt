package com.chardy.doom

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import java.lang.SecurityException

internal enum class InboxLaunchResult { ATTEMPTED, UNAVAILABLE }

internal fun interface InstagramInboxLauncher {
    fun launch(): InboxLaunchResult
}

internal object InstagramInboxIntentFactory {
    fun create(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://www.instagram.com/direct/inbox/")
    ).setPackage("com.instagram.android")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

internal class AndroidInstagramInboxLauncher(
    private val launchIntent: (Intent) -> Unit
) : InstagramInboxLauncher {
    override fun launch(): InboxLaunchResult = try {
        launchIntent(InstagramInboxIntentFactory.create())
        InboxLaunchResult.ATTEMPTED
    } catch (_: ActivityNotFoundException) {
        InboxLaunchResult.UNAVAILABLE
    } catch (_: SecurityException) {
        InboxLaunchResult.UNAVAILABLE
    } catch (_: RuntimeException) {
        InboxLaunchResult.UNAVAILABLE
    }
}
