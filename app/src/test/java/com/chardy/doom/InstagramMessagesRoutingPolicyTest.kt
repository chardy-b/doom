package com.chardy.doom

import org.junit.Assert.assertEquals
import org.junit.Test

class InstagramMessagesRoutingPolicyTest {
    private val actionable = DirectTabSnapshot(
        selected = false,
        visibleToUser = true,
        enabled = true,
        clickable = true,
    )

    @Test fun exactlyOneActionableUnselectedMatchMayBeClicked() {
        assertEquals(DirectTabDecision.CLICK, InstagramMessagesRoutingPolicy.decide(listOf(actionable)))
    }

    @Test fun exactlyOneSelectedMatchCompletesWithoutClick() {
        assertEquals(
            DirectTabDecision.ALREADY_SELECTED,
            InstagramMessagesRoutingPolicy.decide(listOf(actionable.copy(selected = true))),
        )
    }

    @Test fun zeroAndMultipleMatchesFailClosed() {
        assertEquals(DirectTabDecision.REJECT, InstagramMessagesRoutingPolicy.decide(emptyList()))
        assertEquals(
            DirectTabDecision.REJECT,
            InstagramMessagesRoutingPolicy.decide(listOf(actionable, actionable)),
        )
    }

    @Test fun everyActionabilityFailureFailsClosed() {
        listOf(
            actionable.copy(visibleToUser = false),
            actionable.copy(enabled = false),
            actionable.copy(clickable = false),
        ).forEach {
            assertEquals(DirectTabDecision.REJECT, InstagramMessagesRoutingPolicy.decide(listOf(it)))
        }
    }
}
