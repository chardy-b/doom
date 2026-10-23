#!/usr/bin/env python3
"""Validate the real AndroidJUnitRunner XML partition (not device evidence itself)."""
from __future__ import annotations

import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

SUPPLEMENTAL = {
    "com.chardy.doom.DoomUiTest#buildFooterIsReachableAfterScrollingToTheEnd",
    "com.chardy.doom.EntryGateOverlayUiTest#nativeOverlayIsDoomStyledSemanticAndTargeted",
    "com.chardy.doom.EntryGateOverlayUiTest#largeFontAndLandscapeKeepWrappingActionsReachable",
    "com.chardy.doom.EntryGateOverlayUiTest#supplementaryScreenshotsEstablishEachNamedStateAndRestoreConfiguration",
    "com.chardy.doom.EntryGateOverlayUiTest#supplementaryFooterScreenshotScrollsOnlyInItsSeparateTest",
}

SOURCE_ROOT = Path(__file__).resolve().parents[1] / "app/src/androidTest/java/com/chardy/doom"
_KOTLIN_CLASS = re.compile(r"\b(?:class|object)\s+([A-Za-z_]\w*)")
_ANNOTATED_FUNCTION = re.compile(
    r"(?P<annotations>(?:(?:@\s*[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*(?:\s*\([^()\n]*\))?)[\t \r\n]*)+)"
    r"(?:(?:public|private|protected|internal|suspend|inline|final|open|override|operator|infix|tailrec)\s+)*"
    r"fun\s+(?P<name>[A-Za-z_]\w*)"
)


def _mask_kotlin_comments_and_strings(source: str) -> str:
    """Keep Kotlin structure while hiding comments and string contents."""
    result = list(source)
    i = 0
    block_depth = 0
    state = "normal"
    while i < len(source):
        if state == "line":
            if source[i] == "\n":
                state = "normal"
            elif source[i] != "\r":
                result[i] = " "
            i += 1
            continue
        if state == "block":
            if source.startswith("/*", i):
                result[i] = result[i + 1] = " "
                block_depth += 1
                i += 2
            elif source.startswith("*/", i):
                result[i] = result[i + 1] = " "
                block_depth -= 1
                i += 2
                if block_depth == 0:
                    state = "normal"
            else:
                if source[i] not in "\r\n":
                    result[i] = " "
                i += 1
            continue
        if state in {"string", "triple", "char"}:
            end = '"' if state == "string" else "'" if state == "char" else '"""'
            if source.startswith(end, i):
                for j in range(i, i + len(end)):
                    result[j] = " "
                i += len(end)
                state = "normal"
            elif state == "string" and source[i] == "\\" and i + 1 < len(source):
                result[i] = result[i + 1] = " "
                i += 2
            else:
                if source[i] not in "\r\n":
                    result[i] = " "
                i += 1
            continue
        if source.startswith("//", i):
            result[i] = result[i + 1] = " "
            state = "line"
            i += 2
        elif source.startswith("/*", i):
            result[i] = result[i + 1] = " "
            state = "block"
            block_depth = 1
            i += 2
        elif source.startswith('"""', i):
            for j in range(i, i + 3):
                result[j] = " "
            state = "triple"
            i += 3
        elif source[i] == '"':
            result[i] = " "
            state = "string"
            i += 1
        elif source[i] == "'":
            result[i] = " "
            state = "char"
            i += 1
        else:
            i += 1
    return "".join(result)


def source_test_inventory(source_root: Path = SOURCE_ROOT) -> set[str]:
    """Enumerate every class method carrying a JUnit @Test annotation."""
    identities: list[str] = []
    for source_file in sorted(source_root.rglob("*.kt")) if source_root.is_dir() else []:
        source = _mask_kotlin_comments_and_strings(source_file.read_text(encoding="utf-8"))
        package_match = re.search(
            r"^\s*package\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)", source, re.MULTILINE
        )
        if not package_match:
            raise ValueError(f"Kotlin test source has no package: {source_file}")
        package = package_match.group(1)
        braces: list[int] = []
        pairs: dict[int, int] = {}
        for position, character in enumerate(source):
            if character == "{":
                braces.append(position)
            elif character == "}" and braces:
                pairs[braces.pop()] = position
        classes = []
        for match in _KOTLIN_CLASS.finditer(source):
            body_start = source.find("{", match.end())
            if body_start != -1 and body_start in pairs:
                classes.append((body_start, pairs[body_start], match.group(1)))
        for match in _ANNOTATED_FUNCTION.finditer(source):
            annotations = re.findall(
                r"@\s*([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)", match.group("annotations")
            )
            if not any(annotation.rsplit(".", 1)[-1] == "Test" for annotation in annotations):
                continue
            containing = [item for item in classes if item[0] < match.start() < item[1]]
            if not containing:
                raise ValueError(f"@Test method is not inside a class: {source_file}:{match.group('name')}")
            class_name = max(containing, key=lambda item: item[0])[2]
            identities.append(f"{package}.{class_name}#{match.group('name')}")
    found = set(identities)
    if len(found) != len(identities):
        raise ValueError("duplicate @Test identities in Android test source")
    return found


def validate(mode: str, report_root: Path) -> set[str]:
    if mode not in {"canonical", "supplemental"}:
        raise ValueError("mode must be canonical or supplemental")
    reports = sorted(report_root.rglob("TEST-*.xml")) if report_root.is_dir() else []
    if not reports:
        raise ValueError("no Android JUnit XML reports found")
    identities: list[str] = []
    bad = False
    for report in reports:
        root = ET.parse(report).getroot()
        suites = list(root.iter("testsuite"))
        if root.tag == "testsuites":
            suites.append(root)
        for suite in suites:
            if any(int(suite.get(key, "0")) != 0 for key in ("failures", "errors", "skipped")):
                bad = True
        for case in root.iter("testcase"):
            classname, name = case.get("classname"), case.get("name")
            if not classname or not name:
                raise ValueError(f"testcase without identity in {report}")
            identities.append(f"{classname}#{name}")
            if any(case.find(kind) is not None for kind in ("failure", "error", "skipped")):
                bad = True
    found = set(identities)
    if not found:
        raise ValueError("Android instrumentation executed zero tests")
    if len(found) != len(identities):
        raise ValueError("duplicate Android test identities")
    if bad:
        raise ValueError("Android JUnit reports contain failures, errors, or skips")
    source_tests = source_test_inventory()
    if not SUPPLEMENTAL <= source_tests:
        raise ValueError("source inventory is missing an expected supplemental test")
    if mode == "supplemental" and found != SUPPLEMENTAL:
        raise ValueError(f"supplemental filter mismatch: expected 5, found {len(found)}")
    if mode == "canonical" and found != source_tests - SUPPLEMENTAL:
        expected = len(source_tests - SUPPLEMENTAL)
        raise ValueError(f"canonical inventory mismatch: expected {expected}, found {len(found)}")
    return found


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: validate-android-junit.py MODE REPORT_ROOT")
    try:
        found = validate(sys.argv[1], Path(sys.argv[2]))
    except (ValueError, ET.ParseError, OSError) as exc:
        raise SystemExit(f"Invalid Android JUnit partition: {exc}") from exc
    print(f"Validated {len(found)} {sys.argv[1]} Android test identities.")


if __name__ == "__main__":
    main()
