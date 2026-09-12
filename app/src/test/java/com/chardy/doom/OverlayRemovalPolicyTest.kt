package com.chardy.doom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayRemovalPolicyTest {
    @Test fun noActionIsReleasedBeforeConfirmedDetachment() {
        val policy = OverlayRemovalPolicy(maxAttempts = 3)
        policy.request(OverlayRemovalAction.HOME)

        assertEquals(OverlayRemovalDecision.RETRY, policy.failedAttempt())
        assertEquals(OverlayRemovalDecision.RETRY, policy.failedAttempt())
        assertEquals(OverlayRemovalAction.HOME, policy.confirmedDetached())
        assertNull(policy.confirmedDetached())
    }

    @Test fun retryBudgetDisablesServiceWithoutReleasingAction() {
        val policy = OverlayRemovalPolicy(maxAttempts = 2)
        policy.request(OverlayRemovalAction.COMPLETE)

        assertEquals(OverlayRemovalDecision.RETRY, policy.failedAttempt())
        assertEquals(OverlayRemovalDecision.DISABLE_SERVICE, policy.failedAttempt())
    }

    @Test fun explicitActionsOverridePreserveButResetOutsideIsStrongest() {
     val home = OverlayRemovalPolicy()
     home.request(OverlayRemovalAction.HOME)
     home.request(OverlayRemovalAction.PRESERVE_REPORT)
     assertEquals(OverlayRemovalAction.HOME, home.confirmedDetached())

     val completion = OverlayRemovalPolicy()
     completion.request(OverlayRemovalAction.COMPLETE)
     completion.request(OverlayRemovalAction.PRESERVE_REPORT)
     assertEquals(OverlayRemovalAction.PRESERVE_REPORT, completion.confirmedDetached())
     completion.request(OverlayRemovalAction.RESET_OUTSIDE)
     completion.request(OverlayRemovalAction.HOME)
     completion.request(OverlayRemovalAction.RESET_OUTSIDE)
     assertEquals(OverlayRemovalAction.RESET_OUTSIDE, completion.confirmedDetached())
    }

    @Test fun preserveCannotOverridePendingDismiss() {
        val dismiss = OverlayRemovalPolicy()
        dismiss.request(OverlayRemovalAction.BYPASS)
        dismiss.request(OverlayRemovalAction.PRESERVE_REPORT)
        assertEquals(OverlayRemovalAction.BYPASS, dismiss.confirmedDetached())
    }

    @Test fun lowerPriorityActionCannotReplaceSafetyCleanup() {
        val policy = OverlayRemovalPolicy()
        policy.request(OverlayRemovalAction.RESET_OUTSIDE)
        policy.request(OverlayRemovalAction.HOME)
        assertEquals(OverlayRemovalAction.RESET_OUTSIDE, policy.confirmedDetached())
    }

    @Test fun preserveReportIsExplicitAndSurvivesUntilDetachment() {
        val policy = OverlayRemovalPolicy()
        policy.request(OverlayRemovalAction.PRESERVE_REPORT)
        assertEquals(OverlayRemovalAction.PRESERVE_REPORT, policy.confirmedDetached())
    }

    @Test(expected = IllegalArgumentException::class)
    fun retryBudgetMustBePositive() {
        OverlayRemovalPolicy(maxAttempts = 0)
    }
}
