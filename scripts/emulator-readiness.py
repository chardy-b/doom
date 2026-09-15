#!/usr/bin/env python3
"""Bounded, output-sanitized API 35 emulator readiness gate."""
import os
import pathlib
import re
import subprocess
import sys
import time

TOTAL_SECONDS = 120.0
COMMAND_SECONDS = 10.0
PACKAGE = "com.chardyb.doom"
ACTIVITY = f"{PACKAGE}/.MainActivity"


class NotReady(Exception):
    pass


def main(argv: list[str]) -> int:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise NotReady("GitHub Actions is required")
    serial = os.environ.get("ANDROID_SERIAL", "")
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise NotReady("ANDROID_SERIAL is not an allowed emulator serial")
    if len(argv) != 2:
        raise NotReady("one prebuilt APK path is required")
    apk = pathlib.Path(argv[1])
    if (apk != pathlib.Path("app/build/outputs/apk/debug/app-debug.apk") or apk.is_symlink()
            or not apk.is_file() or apk.stat().st_size == 0):
        raise NotReady("the exact prebuilt diagnostic APK is missing or empty")

    deadline = time.monotonic() + TOTAL_SECONDS

    def adb(*args: str) -> str:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise NotReady("total readiness deadline expired")
        try:
            done = subprocess.run(
                ["adb", "-s", serial, *args], check=False,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                timeout=min(COMMAND_SECONDS, remaining), text=True,
            )
        except subprocess.TimeoutExpired as exc:
            raise NotReady("an adb readiness command timed out") from exc
        if done.returncode:
            raise NotReady("an adb readiness command failed")
        return done.stdout

    if adb("get-state").strip() != "device":
        raise NotReady("emulator is not online")
    if adb("shell", "getprop", "sys.boot_completed").strip() != "1":
        raise NotReady("emulator boot is incomplete")
    if adb("shell", "getprop", "ro.build.version.sdk").strip() != "35":
        raise NotReady("emulator is not API 35")
    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise NotReady("target is not an emulator")
    if not adb("shell", "cmd", "package", "path", "android").strip().startswith("package:"):
        raise NotReady("package manager is unavailable")

    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "svc", "power", "stayon", "true")
    adb("shell", "wm", "dismiss-keyguard")
    adb("install", "-r", str(apk))
    adb("shell", "am", "start", "-W", "-n", ACTIVITY)

    policy = adb("shell", "dumpsys", "window", "policy")
    unlocked = (
        re.search(r"\bshowing=false\b", policy) is not None
        or re.search(r"\bmShowingLockscreen=false\b", policy) is not None
        or re.search(r"\bisStatusBarKeyguard=false\b", policy) is not None
        or re.search(r"KeyguardServiceDelegate[^\n]*\bshowing=false\b", policy) is not None
    )
    any_true = re.search(r"\b(?:showing|mShowingLockscreen|isStatusBarKeyguard)=true\b", policy)
    if not unlocked or any_true:
        raise NotReady("keyguard absence was not proved")
    activities = adb("shell", "dumpsys", "activity", "activities")
    if not re.search(r"topResumedActivity=.*com\.chardyb\.doom/\.MainActivity\b", activities):
        raise NotReady("Doom was not proved top-resumed")
    print("Emulator readiness passed (API 35, unlocked, Doom top-resumed).")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv))
    except NotReady as exc:
        print(f"Emulator readiness failed: {exc}", file=sys.stderr)
        raise SystemExit(2)
