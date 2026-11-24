#!/usr/bin/env python3
"""Print every skipped test of a pgjdbc test run together with the reason it was skipped.

Gradle 7.x writes skipped tests to its JUnit XML reports without the assumption message, and
Surefire's console summary prints only a count, so this report lists each skipped test case and
its reason: the message recorded in the XML report when there is one, otherwise the
assume*/@Ignore/@Disabled lines of the test method (or of its class and superclass) in the
test sources.

Usage: seal-skip-report.py <root-dir>  (searched recursively for TEST-*.xml and src/test/java)
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

SKIP_MARKERS = re.compile(r"assume|Assume|@Ignore|@Disabled|@EnabledIf|@DisabledIf|assumption")


def index_sources(root):
    sources = {}
    for dirpath, _, files in os.walk(root):
        if os.sep + os.path.join("src", "test", "java") not in dirpath + os.sep:
            continue
        base = dirpath.split(os.path.join("src", "test", "java"))[0]
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, os.path.join(base, "src", "test", "java"))
            sources.setdefault(rel[:-5].replace(os.sep, "."), path)
    return sources


def read_lines(path):
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read().splitlines()


def marker_lines(lines):
    return [line.strip() for line in lines if SKIP_MARKERS.search(line) and not line.startswith("import ")]


def method_markers(lines, method):
    pattern = re.compile(r"\b(void|public|protected)\b.*\b" + re.escape(method) + r"\s*\(")
    for idx, line in enumerate(lines):
        if pattern.search(line):
            start = idx
            while start > 0 and lines[start - 1].strip().startswith("@"):
                start -= 1
            end = idx + 1
            while end < len(lines) and "@Test" not in lines[end]:
                end += 1
            return marker_lines(lines[start:end])
    return []


def superclass(lines, sources, cls):
    package = cls.rsplit(".", 1)[0]
    for line in lines:
        match = re.search(r"\bclass\s+\w+(?:<[^>]*>)?\s+extends\s+(\w+)", line)
        if match:
            simple = match.group(1)
            for candidate in sources:
                if candidate.endswith("." + simple) and (
                    candidate.startswith(package) or "import " + candidate + ";" in "\n".join(lines)
                ):
                    return candidate
    return None


def source_reason(sources, cls, test_name):
    outer = cls.split("$")[0]
    path = sources.get(outer)
    if not path:
        return None
    lines = read_lines(path)
    method = re.split(r"[\[(]", test_name, 1)[0].strip()
    found = method_markers(lines, method)
    if found:
        return "test method guard: " + " | ".join(found)
    found = marker_lines(lines)
    parent = superclass(lines, sources, outer)
    if parent:
        found += marker_lines(read_lines(sources[parent]))
    if found:
        return "class-level guard (assumption in setup/base class): " + " | ".join(dict.fromkeys(found))
    return None


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    sources = index_sources(root)
    total = 0
    print("==== SKIPPED TEST REPORT (%s) ====" % root)
    leg = os.environ.get("SEAL_LEG")
    if leg:
        print("Leg configuration: %s" % leg)
        print(
            "Upstream's randomized CI matrix turns ssl, scram, replication, xa, gss and slow tests on only in"
            " the legs whose configuration lists them (no_<feature> = disabled in this leg); tests guarded on"
            " a disabled feature or on a newer server version are skipped here and run in the legs where the"
            " feature/version is available."
        )
    for dirpath, _, files in sorted(os.walk(root)):
        for name in sorted(files):
            if not (name.startswith("TEST-") and name.endswith(".xml")):
                continue
            try:
                tree = ET.parse(os.path.join(dirpath, name))
            except ET.ParseError:
                continue
            for case in tree.iter("testcase"):
                skipped = case.find("skipped")
                if skipped is None:
                    continue
                total += 1
                cls = case.get("classname", "?")
                test = case.get("name", "?")
                message = (skipped.get("message") or (skipped.text or "")).strip()
                guard = source_reason(sources, cls, test)
                reason = "; ".join(part for part in (message, guard) if part)
                if not reason:
                    reason = "no guard in the test source: excluded by the JUnit tag/parameter filter of this run"
                print("SKIPPED %s > %s -- reason: %s" % (cls, test, reason.replace("\n", " ")[:400]))
    print("==== %d skipped test(s) listed above, each with its reason ====" % total)


if __name__ == "__main__":
    main()
