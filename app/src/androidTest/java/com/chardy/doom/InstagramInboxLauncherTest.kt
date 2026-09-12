package com.chardy.doom

import android.content.ActivityNotFoundException
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramInboxLauncherTest {
    @Test fun intentIsConstantPackageTargetedAndNewTaskOnly() {
        val intent = InstagramInboxIntentFactory.create()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://www.instagram.com/direct/inbox/", intent.dataString)
        assertEquals("com.instagram.android", intent.`package`)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertTrue(intent.extras == null)
        assertNull(intent.selector)
        assertNull(intent.categories)
    }

    @Test fun normalReturnIsAttemptedAndFailuresAreUnavailableWithoutFallback() {
        var calls = 0
        var captured: Intent? = null
        val launcher = AndroidInstagramInboxLauncher { intent -> calls++; captured = intent }
        assertEquals(InboxLaunchResult.ATTEMPTED, launcher.launch())
        assertEquals(1, calls)
        assertNotNull(captured)

        assertEquals(InboxLaunchResult.UNAVAILABLE,
            AndroidInstagramInboxLauncher { throw ActivityNotFoundException() }.launch())
        assertEquals(InboxLaunchResult.UNAVAILABLE,
            AndroidInstagramInboxLauncher { throw SecurityException() }.launch())
        assertEquals(InboxLaunchResult.UNAVAILABLE,
            AndroidInstagramInboxLauncher { throw IllegalStateException() }.launch())
    }
}
