#!/usr/bin/env python3
"""Host contracts for the canonical/supplemental CI boundary."""
from pathlib import Path
import os, re, shutil, subprocess, tempfile, unittest
ROOT=Path(__file__).resolve().parents[1]
SUP={
'DoomUiTest#buildFooterIsReachableAfterScrollingToTheEnd',
'EntryGateOverlayUiTest#nativeOverlayIsDoomStyledSemanticAndTargeted',
'EntryGateOverlayUiTest#largeFontAndLandscapeKeepWrappingActionsReachable',
'EntryGateOverlayUiTest#supplementaryScreenshotsEstablishEachNamedStateAndRestoreConfiguration',
'EntryGateOverlayUiTest#supplementaryFooterScreenshotScrollsOnlyInItsSeparateTest'}
class SourceContracts(unittest.TestCase):
 def test_workflow_isolation_and_preparation(self):
  can=(ROOT/'.github/workflows/android.yml').read_text(); sup=(ROOT/'.github/workflows/android-supplemental.yml').read_text()
  self.assertNotIn('\n  fixture:',can); self.assertNotIn('ci-fixture.sh',can); self.assertNotIn('ci-overlay.sh',can)
  self.assertNotIn('continue-on-error',can+sup); self.assertNotIn('test-entry-gate-host.py',can)
  self.assertEqual(2,(can+sup).count('android-emulator-runner@'))
  for task in (':app:assembleDebugAndroidTest',':fixturegate:assembleDebugAndroidTest',':fixtureapp:lintDebug'):
   self.assertIn(task,sup)
  self.assertLess(sup.index(':app:assembleDebugAndroidTest'),sup.index('android-emulator-runner@'))
  self.assertIn('script: bash scripts/ci-supplemental.sh',sup)
 def test_exact_method_marker_boundary(self):
  marker=(ROOT/'app/src/androidTest/java/com/chardy/doom/SupplementalEvidence.kt').read_text()
  self.assertIn('AnnotationTarget.FUNCTION',marker); self.assertIn('AnnotationRetention.RUNTIME',marker)
  found=set()
  for path in (ROOT/'app/src/androidTest/java/com/chardy/doom').glob('*.kt'):
   text=path.read_text()
   for m in re.finditer(r'@Test\s+@SupplementalEvidence\s+fun\s+(\w+)',text): found.add(path.stem+'#'+m.group(1))
  self.assertEqual(SUP,found)
  for name in ('EntryGateServiceActionTest.kt','InstagramMessagesRouterTest.kt','StructuralDiagnosticUiTest.kt','RemovalTraceUiTest.kt'):
   self.assertNotIn('@SupplementalEvidence',(ROOT/'app/src/androidTest/java/com/chardy/doom'/name).read_text())
 def test_filters_and_canonical_four(self):
  can=(ROOT/'scripts/ci-device.sh').read_text(); sup=(ROOT/'scripts/ci-overlay.sh').read_text()
  self.assertIn('notAnnotation=com.chardy.doom.SupplementalEvidence',can)
  self.assertIn('annotation=com.chardy.doom.SupplementalEvidence',sup)
  self.assertNotIn('doom-overlay-ui-evidence',can)
  self.assertIn('validate-android-junit.py canonical',can); self.assertIn('validate-android-junit.py supplemental',sup)
  self.assertIn('evidence-manifest.py',can)
 def test_required_service_regressions_and_rename(self):
  text=(ROOT/'app/src/androidTest/java/com/chardy/doom/EntryGateServiceActionTest.kt').read_text()
  for name in ('serviceCooldownSurvivesDetachAndReentryUntilExactBoundary','failedServiceInstallDoesNotArmCooldown','revokedAdmissionDoesNotArmCooldown','recreatedServiceStartsWithoutCooldown','messagesRouteRejectsForeignOrUnattributedRootAfterDetach'):
   self.assertIn(name,text)
  self.assertNotIn('foreignOrMissingForegroundSuppressesRouteAndHome',text)
class CoordinatorMatrix(unittest.TestCase):
 def run_case(self,overlay,fixture):
  with tempfile.TemporaryDirectory() as td:
   root=Path(td); (root/'scripts').mkdir(); (root/'evidence').mkdir()
   shutil.copy(ROOT/'scripts/ci-supplemental.sh',root/'scripts')
   for name,code in [('ci-overlay.sh',overlay),('ci-fixture.sh',fixture)]:
    (root/'scripts'/name).write_text(f'#!/bin/bash\necho {name} >> calls\nexit {code}\n')
   bindir=root/'bin'; bindir.mkdir(); (bindir/'git').write_text('#!/bin/bash\n[[ "$1" == status ]] || echo '+('a'*40)+'\n'); (bindir/'git').chmod(0o755)
   env=os.environ|{'PATH':str(bindir)+':'+os.environ['PATH'],'GITHUB_ACTIONS':'true','CANDIDATE_SHA':'a'*40,'GITHUB_RUN_ID':'1','GITHUB_RUN_ATTEMPT':'2'}
   got=subprocess.run(['bash','scripts/ci-supplemental.sh'],cwd=root,env=env,capture_output=True,text=True)
   return got.returncode,(root/'calls').read_text().splitlines(),got.stdout
 def test_success_and_failure_precedence(self):
  for oe,fe,want in ((0,0,0),(7,0,7),(0,8,8),(7,8,7)):
   code,calls,out=self.run_case(oe,fe); self.assertEqual(want,code); self.assertEqual(['ci-overlay.sh','ci-fixture.sh'],calls); self.assertIn(f'overlay_exit={oe} fixture_exit={fe}',out)
if __name__=='__main__': unittest.main(verbosity=2)
