package com.chardy.doom
import org.junit.Assert.*
import org.junit.Test
class EntryGateOverlayModelTest {
 @Test fun modelMapsRemainingToSharedFrame(){val start=EntryGateOverlayModel.from(20_000,20_000,false);assertEquals("Breathe in",start.frame.label);assertEquals(listOf(0f,0f),start.frame.segments);val middle=EntryGateOverlayModel.from(5_000,20_000,false);assertEquals(listOf(1f,.5f),middle.frame.segments)}
 @Test fun completionFillsEverySegment(){assertEquals(listOf(1f,1f,1f),EntryGateOverlayModel.from(0,30_000,true).frame.segments)}
 @Test fun animationTimelineAdvancesAtDisplayFrameIntervalsWithoutChangingDuration(){
  assertEquals(2_000L, BreathingAnimationTimeline.elapsedAt(2_000L,1_000_000_000L,1_000_000_000L,10_000L))
  assertEquals(2_016L, BreathingAnimationTimeline.elapsedAt(2_000L,1_000_000_000L,1_016_666_667L,10_000L))
  assertEquals(10_000L, BreathingAnimationTimeline.elapsedAt(9_990L,1_000_000_000L,2_000_000_000L,10_000L))
 }
}
