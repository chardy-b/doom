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
INSTALL_SECONDS = 60.0
POLL_SECONDS = 1.0
PORT_MIN = 5554
PORT_MAX = 5682
PACKAGE = "com.chardyb.doom"
ACTIVITY = f"{PACKAGE}/.MainActivity"


class NotReady(Exception):
    pass


def emulator_serial(value: str) -> str:
    if re.fullmatch(r"[0-9]+", value) is None:
        raise NotReady("EMULATOR_PORT is not a decimal emulator port")
    port = int(value)
    if port < PORT_MIN or port > PORT_MAX or port % 2:
        raise NotReady("EMULATOR_PORT is outside the allowed emulator console-port range")
    return f"emulator-{port}"


def keyguard_is_unlocked(policy: str) -> bool:
    active = (
        r"\bisStatusBarKeyguard\s*=\s*true\b",
        r"\bmShowingLockscreen\s*=\s*true\b",
        r"\bmKeyguardShowing\s*=\s*true\b",
        r"\bkeyguardShowing\s*=\s*true\b",
    )
    if any(re.search(pattern, policy) for pattern in active):
        return False
    delegate_blocks = re.findall(
        r"(?ms)^\s*KeyguardServiceDelegate\b.*?(?=^\S|\Z)", policy
    )
    if any(re.search(r"\bshowing\s*=\s*true\b", block) for block in delegate_blocks):
        return False
    unlocked = (
        r"\bisStatusBarKeyguard\s*=\s*false\b",
        r"\bmShowingLockscreen\s*=\s*false\b",
    )
    signals = sum(bool(re.search(pattern, policy)) for pattern in unlocked)
    signals += sum(bool(re.search(r"\bshowing\s*=\s*false\b", block))
                   for block in delegate_blocks)
    return signals > 0


def has_emulator_identity(boot_qemu: str | None, kernel_qemu: str | None) -> bool:
    return (boot_qemu or "").strip() == "1" or (kernel_qemu or "").strip() == "1"


def main(argv: list[str]) -> int:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise NotReady("GitHub Actions is required")
    serial = emulator_serial(os.environ.get("EMULATOR_PORT", ""))
    if len(argv) != 2:
        raise NotReady("one prebuilt APK path is required")
    apk = pathlib.Path(argv[1])
    expected = pathlib.Path("app/build/outputs/apk/debug/app-debug.apk")
    if apk != expected or apk.is_symlink() or not apk.is_file() or apk.stat().st_size == 0:
        raise NotReady("the exact prebuilt diagnostic APK is missing or empty")

    deadline = time.monotonic() + TOTAL_SECONDS

    def remaining() -> float:
        left = deadline - time.monotonic()
        if left <= 0:
            raise NotReady("total readiness deadline expired")
        return left

    def adb(*args: str, install: bool = False) -> str | None:
        limit = INSTALL_SECONDS if install else COMMAND_SECONDS
        try:
            done = subprocess.run(
                ["adb", "-s", serial, *args], check=False,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                timeout=min(limit, remaining()), text=True,
            )
        except subprocess.TimeoutExpired:
            return None
        return done.stdout if done.returncode == 0 else None

    def poll(description: str, probe):
        while True:
            value = probe()
            if value:
                return value
            left = remaining()
            time.sleep(min(POLL_SECONDS, left))

    poll("online", lambda: (adb("get-state") or "").strip() == "device")
    poll("boot", lambda: (adb("shell", "getprop", "sys.boot_completed") or "").strip() == "1")
    poll("API", lambda: (adb("shell", "getprop", "ro.build.version.sdk") or "").strip() == "35")

    def is_emulator():
        boot = adb("shell", "getprop", "ro.boot.qemu")
        kernel = adb("shell", "getprop", "ro.kernel.qemu")
        return has_emulator_identity(boot, kernel)

    poll("emulator identity", is_emulator)
    poll("package manager", lambda: (adb("shell", "cmd", "package", "path", "android") or "").startswith("package:"))

    def wake_and_unlock():
        for args in (("shell", "input", "keyevent", "KEYCODE_WAKEUP"),
                     ("shell", "svc", "power", "stayon", "true"),
                     ("shell", "wm", "dismiss-keyguard")):
            if adb(*args) is None:
                return False
        return keyguard_is_unlocked(adb("shell", "dumpsys", "window", "policy") or "")

    poll("unlocked keyguard", wake_and_unlock)
    poll("APK install", lambda: adb("install", "-r", str(apk), install=True) is not None)

    def launch_and_prove():
        if adb("shell", "am", "start", "-W", "-n", ACTIVITY) is None:
            return False
        activities = adb("shell", "dumpsys", "activity", "activities") or ""
        return re.search(r"topResumedActivity=.*com\.chardyb\.doom/\.MainActivity\b", activities) is not None

    poll("Doom foreground", launch_and_prove)
    # Instrumentation must start from a fresh package after the launch proof.
    poll("clear Doom data", lambda: adb("shell", "pm", "clear", PACKAGE) is not None)
    poll("stop Doom", lambda: adb("shell", "am", "force-stop", PACKAGE) is not None)
    print("Emulator readiness passed (API 35, unlocked, Doom top-resumed, app data cleared).")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv))
    except NotReady as exc:
        print(f"Emulator readiness failed: {exc}", file=sys.stderr)
        raise SystemExit(2)
