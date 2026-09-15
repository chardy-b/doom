package com.chardy.doom
import org.junit.Assert.*
import org.junit.Test
class EntryGateOverlayModelTest {
 @Test fun modelMapsRemainingToSharedFrame(){val start=EntryGateOverlayModel.from(20_000,20_000,false);assertEquals("Breathe in",start.frame.label);assertEquals(listOf(0f,0f),start.frame.segments);val middle=EntryGateOverlayModel.from(5_000,20_000,false);assertEquals(listOf(1f,.5f),middle.frame.segments)}
 @Test fun completionFillsEverySegment(){assertEquals(listOf(1f,1f,1f),EntryGateOverlayModel.from(0,30_000,true).frame.segments)}
}
