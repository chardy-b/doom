"""Bounded CI emulator/storage readiness; imported with mocked I/O by host tests."""
import os
import re
import subprocess
import time


TIMEOUT_SECONDS = 120
COMMAND_TIMEOUT_SECONDS = 10
POLL_SECONDS = 2
STORAGE_PROBE = """test -d /sdcard && mkdir -p /sdcard/Download || exit 1
probe=$(mktemp /sdcard/Download/.doom-fixture-ready.XXXXXX) || exit 1
trap 'rm -f "$probe"' EXIT
printf '%s' doom-fixture-ready > "$probe" &&
test "$(cat "$probe")" = doom-fixture-ready && rm -f "$probe"
"""


def wait_ready(serial):
    start = time.monotonic()
    deadline = start + TIMEOUT_SECONDS
    attempt = 0
    stage, status = "identity", "invalid-serial"

    def report(outcome):
        level = "error" if outcome in {"timeout", "rejected"} else "notice"
        print(f"::{level} title=Fixture readiness::phase=prepare readiness={outcome} "
              f"stage={stage} status={status} attempt={attempt} "
              f"elapsed_s={time.monotonic() - start:.1f} timeout_s={TIMEOUT_SECONDS}", flush=True)

    # The pinned emulator runner supplies this serial to both adb and Gradle.
    # Never fall back to an arbitrary attached device.
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        report("rejected")
        return 2

    checks = [
        ("online", ["get-state"], "device"),
        ("boot", ["shell", "getprop", "sys.boot_completed"], "1"),
        ("sdk", ["shell", "getprop", "ro.build.version.sdk"], "35"),
        ("emulator", ["shell", "getprop", "ro.kernel.qemu"], "1"),
        ("storage", ["shell", STORAGE_PROBE], None),
    ]
    while time.monotonic() < deadline:
        attempt += 1
        for stage, args, expected in checks:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                status = "deadline"
                break
            try:
                result = subprocess.run(
                    ["adb", "-s", serial, *args], capture_output=True, text=True,
                    timeout=min(COMMAND_TIMEOUT_SECONDS, remaining), check=False,
                )
            except subprocess.TimeoutExpired:
                status = "adb-timeout"
                break
            except OSError:
                status = "adb-unavailable"
                report("rejected")
                return 2
            if result.returncode:
                status = f"adb-exit-{result.returncode}"
                break
            value = result.stdout.strip()
            if expected is not None and value != expected:
                status = "empty" if not value else "unexpected-value"
                # Empty properties can be transient; a wrong identity cannot pass.
                if value and stage in {"sdk", "emulator"}:
                    report("rejected")
                    return 2
                break
        else:
            if time.monotonic() < deadline:
                status = "writable"
                report("ready")
                return 0
            status = "deadline"
        remaining = deadline - time.monotonic()
        if remaining > 0:
            report("waiting")
            time.sleep(min(POLL_SECONDS, remaining))
    report("timeout")
    return 124


if __name__ == "__main__":
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("Fixture readiness execution is restricted to GitHub Actions.")
    raise SystemExit(wait_ready(os.environ.get("ANDROID_SERIAL", "")))
