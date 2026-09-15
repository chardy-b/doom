package com.chardy.doom
import org.junit.Assert.*
import org.junit.Test

class ConfigurableGateTest {
 @Test fun admissionRequiresAllFourAuthoritiesAndDurationIsSnapshot(){
  var reminder=false;var report=true;var gateConsent=true;var connected=true;var duration=10_000L
  val gate=InstagramEntryGate(10_000,{reminder&&report&&gateConsent&&connected},{0},durationProvider={duration})
  assertEquals(EntryGateState.BYPASSED,gate.beginInstagramSession().let{gate.state})
  reminder=true;val ticket=gate.beginInstagramSession();assertTrue(gate.observeInstagram(0,ticket));assertTrue(gate.overlayShown(0,ticket));duration=30_000
  assertEquals(1,gate.remainingMs(9_999,ticket));assertTrue(gate.complete(10_000,ticket))
 }
 @Test fun cooldownDurationIsAnEpisodeAdmissionSnapshot(){
  var now=0L;var cooldown=60_000L
  val gate=InstagramEntryGate(10_000,{true},{now},cooldownDurationProvider={cooldown})
  val first=gate.beginInstagramSession();assertTrue(gate.observeInstagram(now,first));assertTrue(gate.overlayShown(now,first))
  cooldown=300_000;now=10_000;assertTrue(gate.complete(now,first));gate.leaveInstagram()
  now=69_999;assertTrue(gate.cooldownActive());now=70_000;assertFalse(gate.cooldownActive())
  val second=gate.beginInstagramSession();assertTrue(gate.observeInstagram(now,second));assertTrue(gate.overlayShown(now,second))
  cooldown=60_000;now=80_000;assertTrue(gate.complete(now,second));gate.leaveInstagram()
  now=379_999;assertTrue(gate.cooldownActive());now=380_000;assertFalse(gate.cooldownActive())
 }
}
