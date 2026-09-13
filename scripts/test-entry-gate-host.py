#!/usr/bin/env python3
"""Compile and run Doom's pure Kotlin/JUnit suite without Gradle or Android SDK."""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile

REPO = Path(__file__).resolve().parents[1]
MAIN = [
    "DemoGate.kt",
    "InstagramEntryGate.kt",
    "InstagramSurfaceShadowClassifier.kt",
    "OverlayRemovalPolicy.kt",
    "OverlayCallbackGuard.kt",
    "OverlayForegroundWatchdog.kt",
    "EntryGateOverlayModel.kt",
    "BreathingVisuals.kt",
    "SanitizedStructuralReport.kt",
]
TESTS = [
    "GatePolicyTest.kt",
    "InstagramEntryGateTest.kt",
    "InstagramSurfaceShadowClassifierTest.kt",
    "OverlayRemovalPolicyTest.kt",
    "OverlayCallbackGuardTest.kt",
    "OverlayForegroundWatchdogTest.kt",
    "EntryGateOverlayModelTest.kt",
    "BreathingVisualsTest.kt",
    "SanitizedStructuralReportTest.kt",
]
TEST_CLASSES = [
    "com.chardy.doom.GatePolicyTest",
    "com.chardy.doom.InstagramEntryGateTest",
    "com.chardy.doom.InstagramSurfaceShadowClassifierTest",
    "com.chardy.doom.OverlayRemovalPolicyTest",
    "com.chardy.doom.OverlayCallbackGuardTest",
    "com.chardy.doom.OverlayForegroundWatchdogTest",
    "com.chardy.doom.EntryGateOverlayModelTest",
    "com.chardy.doom.BreathingVisualsTest",
    "com.chardy.doom.SanitizedStructuralReportTest",
]


def locate_gradle_lib() -> Path:
    explicit = os.environ.get("DOOM_GRADLE_LIB")
    candidates = [Path(explicit)] if explicit else []
    candidates += [
        Path.home() / ".local/opt/gradle-8.10.2/lib",
        Path.home() / ".gradle/wrapper/dists",
        Path("/mnt/HC_Volume_106820083/cache/gradle/wrapper/dists"),
    ]
    for candidate in candidates:
        if not candidate.exists():
            continue
        jars = list(candidate.rglob("kotlin-compiler-embeddable-*.jar"))
        for compiler in sorted(jars, reverse=True):
            lib = compiler.parent
            if ((lib / "junit-4.13.2.jar").exists() and
                    (lib / "hamcrest-core-1.3.jar").exists() and
                    list(lib.glob("kotlin-stdlib-*.jar"))):
                return lib
    raise SystemExit("No compatible cached Gradle Kotlin/JUnit library directory found")


def main() -> None:
    lib = locate_gradle_lib()
    compiler_cp = os.pathsep.join(str(path) for path in sorted(lib.glob("*.jar")))
    junit = lib / "junit-4.13.2.jar"
    hamcrest = lib / "hamcrest-core-1.3.jar"
    stdlib = sorted(lib.glob("kotlin-stdlib-*.jar"))[0]
    source_cp = os.pathsep.join(map(str, (junit, hamcrest, stdlib)))
    sources = [REPO / "app/src/main/java/com/chardy/doom" / name for name in MAIN]
    sources += [REPO / "app/src/test/java/com/chardy/doom" / name for name in TESTS]
    missing = [str(path) for path in sources if not path.exists()]
    if missing:
        raise SystemExit(f"Missing sources: {missing}")

    with tempfile.TemporaryDirectory(prefix="doom-entry-gate-") as tmp:
        classes = Path(tmp) / "classes"
        classes.mkdir()
        subprocess.run([
            "java", "-cp", compiler_cp,
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib", "-no-reflect", "-jvm-target", "17",
            "-classpath", source_cp, "-d", str(classes),
            *(str(path) for path in sources),
        ], check=True, cwd=REPO)
        runtime_cp = os.pathsep.join(map(str, (classes, junit, hamcrest, stdlib)))
        subprocess.run([
            "java", "-cp", runtime_cp, "org.junit.runner.JUnitCore", *TEST_CLASSES
        ], check=True, cwd=REPO)


if __name__ == "__main__":
    main()
