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
 @Test fun animationTimelineClampsInvalidDurationsAndHostileAnchorsLikeFrames(){
  listOf(-10L,0L,1L,9_999L).forEach { duration ->
   assertEquals(0L,BreathingAnimationTimeline.elapsedAt(-5L,2_000_000L,1_000_000L,duration))
   assertEquals(BREATH_MS,BreathingAnimationTimeline.elapsedAt(Long.MAX_VALUE,0L,Long.MAX_VALUE,duration))
   assertEquals(BREATH_MS,EntryGateOverlayModel.from(-1L,duration,false).durationMs)
  }
  assertEquals(15_000L,BreathingAnimationTimeline.elapsedAt(14_999L,0L,2_000_000L,15_000L))
 }
}
