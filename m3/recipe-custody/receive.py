#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
"""Receive hash-verified OpenRewrite SDK Results serially after admitting every task input."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import tempfile
import xml.etree.ElementTree as ET

OWNERS = ("M3EditScope.java", "M3HashPinnedJavaSnapshotRecipe.java",
          "M3HashPinnedTextSnapshotRecipe.java", "M3ScopeInference.java")
TESTS = {"exactReplayFixedPointAndInputOrder", "mixedPreimagesAndPostimages",
         "driftMissingDuplicateAndWrongRepresentationRefuse", "postScanChangesRefuse",
         "qualifiedSdkResultsAreRetained"}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def rows(path, width):
    result = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        cells = line.split("\t")
        if len(cells) != width:
            raise ValueError("invalid receipt row: " + str(path))
        result.append(cells)
    if not result or len({row[0] for row in result}) != len(result):
        raise ValueError("empty or duplicate receipt rows: " + str(path))
    return result


def contained(root, relative):
    part = PurePosixPath(relative)
    if (not relative or str(part) != relative or part.is_absolute() or ".." in part.parts or ".git" in part.parts
            or "\\" in relative or any(c in relative for c in "\t\r\n\0")):
        raise ValueError("invalid receiving path: " + repr(relative))
    path = root / relative
    cursor = path
    while cursor != root:
        if cursor.is_symlink():
            raise ValueError("symlink target or parent is not admitted: " + relative)
        cursor = cursor.parent
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError("receiving path escapes root: " + relative)
    if path.exists() and not path.is_file():
        raise ValueError("receiving source is not a regular file: " + relative)
    return path


def qualification(crate, dataset, owner):
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,31}", dataset):
        raise ValueError("invalid dataset")
    data_root = crate / "datasets" / dataset
    metadata = json.loads(contained(data_root, "DATASET.json").read_text(encoding="utf-8"))
    build = crate / "target" / dataset
    candidate = build / "candidate"
    report = contained(build, "surefire-reports/TEST-com.synexia.rewrite.M3TaskRecipeTest.xml")
    suite = ET.parse(report).getroot()
    if (int(suite.attrib.get("tests", "0")) != len(TESTS)
            or any(int(suite.attrib.get(key, "0")) for key in ("failures", "errors", "skipped"))
            or {case.attrib.get("name") for case in suite.findall("testcase")} != TESTS):
        raise ValueError("all actual SDK qualification tests must execute successfully")
    properties = {item.attrib["name"]: item.attrib.get("value", "")
                  for item in suite.findall("./properties/property")}
    if properties.get("m3.dataset") != dataset or properties.get("m3.crate.name") != metadata["crate_name"]:
        raise ValueError("SDK qualification belongs to another dataset")
    bound = dict(rows(contained(candidate, "SDK_INPUTS.tsv"), 2))
    expected_paths = {"crate/pom.xml", "crate/receive.py", "crate/prepare.py", "crate/ENGINE_SOURCES.tsv"}
    for directory in (crate / "src", crate / "contract", data_root):
        if directory.exists():
            expected_paths.update("crate/" + p.relative_to(crate).as_posix()
                                  for p in directory.rglob("*") if p.is_file())
    expected_paths.update("owner/src/main/java/com/synexia/rewrite/" + name for name in OWNERS)
    if set(bound) != expected_paths:
        raise ValueError("incomplete or extra SDK source binding")
    for path, expected in bound.items():
        domain, relative = path.split("/", 1)
        if domain not in ("crate", "owner"):
            raise ValueError("unknown qualification input domain")
        actual = contained(crate if domain == "crate" else owner, relative).read_bytes()
        if digest(actual) != expected:
            raise ValueError("recipe changed after SDK qualification: " + path)
    effective_engines = rows(data_root / "ENGINE_EFFECTIVE.tsv", 3)
    if {row[0] for row in effective_engines} != {"src/main/java/com/synexia/rewrite/" + name for name in OWNERS}:
        raise ValueError("incomplete effective canonical engine set")
    for relative, blob, expected in effective_engines:
        data = contained(owner, relative).read_bytes()
        actual_blob = hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()
        if digest(data) != expected or actual_blob != blob:
            raise ValueError("canonical engine custody mismatch: " + relative)
    manifests = {}
    preimages = {}
    resource_root = data_root / "resources/com/synexia/rewrite"
    for kind in ("java", "text"):
        lane = resource_root / ("hash-pinned-" + kind) / metadata["crate_name"]
        manifest = lane / "manifest.tsv"
        if not manifest.exists():
            continue
        previous = ""
        for path, before, after, resource in rows(manifest, 4):
            if path <= previous or path in manifests:
                raise ValueError("unordered or duplicate Java/text target: " + path)
            if path.endswith(".java") != (kind == "java"):
                raise ValueError("incorrect source lane: " + path)
            if digest(contained(lane, resource).read_bytes()) != after:
                raise ValueError("template changed: " + path)
            if before != "ABSENT":
                preimage = contained(lane, "pre/" + resource).read_bytes()
                if digest(preimage) != before:
                    raise ValueError("preimage changed: " + path)
                preimages[path] = preimage
            manifests[path] = (before, after)
            previous = path
    if not manifests or len(manifests) != metadata["targets"]:
        raise ValueError("empty or incomplete task manifests")
    modes = dict(rows(data_root / "MODES.tsv", 2))
    if set(modes) != set(manifests) or any(not re.fullmatch(r"0[0-7]{3}", mode) for mode in modes.values()):
        raise ValueError("invalid target modes")
    actual_rows = rows(contained(candidate, "SDK_RESULTS.tsv"), 3)
    if {row[0] for row in actual_rows} != set(manifests):
        raise ValueError("SDK result target set differs from qualified manifests")
    outputs = {}
    for path, before, after in sorted(actual_rows):
        if (before, after) != manifests[path]:
            raise ValueError("SDK result manifest mismatch: " + path)
        output = contained(candidate, path).read_bytes()
        if digest(output) != after:
            raise ValueError("SDK result changed after qualification: " + path)
        outputs[path] = output
    return {"metadata": metadata, "manifests": manifests, "preimages": preimages,
            "outputs": outputs, "modes": {path: int(mode, 8) for path, mode in modes.items()},
            "bound": bound, "report": report, "candidate": candidate}


def receive(crate, dataset, owner, target, check=False):
    if target.is_symlink() or not target.is_dir():
        raise ValueError("target root must be an existing real directory")
    qualified = qualification(crate, dataset, owner)
    pending = []
    for relative, output in sorted(qualified["outputs"].items()):
        path = contained(target, relative)
        before, after = qualified["manifests"][relative]
        current = digest(path.read_bytes()) if path.exists() else "ABSENT"
        if current not in (before, after):
            raise ValueError("target source drift: " + relative)
        if current != after:
            mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else qualified["modes"][relative]
            pending.append((relative, path, output, current, mode))
    # The caller owns an isolated worktree. Admit all files before the first serial write.
    # Atomic per-file replacement does not claim a cross-file filesystem transaction.
    if not check:
        for relative, path, data, admitted, mode in pending:
            contained(target, relative)
            current = digest(path.read_bytes()) if path.exists() else "ABSENT"
            if current != admitted:
                raise ValueError("target changed after admission: " + relative)
            path.parent.mkdir(parents=True, exist_ok=True)
            descriptor, temporary = tempfile.mkstemp(prefix=".m3-recipe-", dir=path.parent)
            try:
                with os.fdopen(descriptor, "wb") as stream:
                    stream.write(data)
                os.chmod(temporary, mode)
                os.replace(temporary, path)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
    return {"dataset": dataset, "targets": len(qualified["outputs"]), "changes": len(pending),
            "mode": "check" if check else "apply", "actual_sdk_results": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, type=Path)
    parser.add_argument("--crate", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--recipe-owner", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    crate = args.crate.absolute()
    owner = args.recipe_owner or (crate / "sdk-owner" if (crate / "sdk-owner").is_dir() else crate / "../..")
    result = receive(crate, args.dataset, owner.absolute(), args.target.absolute(), args.check)
    print("M3_AST_BUILD_RECEIVER_PASS " + json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
