#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
"""Qualify this task's thin receiver against actual SDK Results in isolated overlays."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
SENTINEL = "unrelated/receiver-sentinel.txt"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def tree_state(root):
    result = {}

    def visit(path):
        info = path.lstat()
        item = {"mode": stat.S_IMODE(info.st_mode), "mtime_ns": info.st_mtime_ns}
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            result[relative] = dict(item, kind="symlink", link=os.readlink(path))
        elif path.is_dir():
            result[relative] = dict(item, kind="directory")
            for child in sorted(path.iterdir()):
                visit(child)
        else:
            result[relative] = dict(item, kind="file", sha256=sha(path.read_bytes()))
    visit(root)
    return result


def execute(crate, dataset, owner, output):
    spec = importlib.util.spec_from_file_location("m3_ast_build_receiver", crate / "receive.py")
    receiver = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(receiver)
    qualified = receiver.qualification(crate, dataset, owner)
    output.mkdir(parents=True, exist_ok=False)
    evidence = output / "evidence"
    evidence.mkdir()
    count = len(qualified["outputs"])
    changes = sum(before != after for before, after in qualified["manifests"].values())
    captured = {}
    for label in qualified["bound"]:
        domain, relative = label.split("/", 1)
        path = (crate if domain == "crate" else owner) / relative
        captured[label] = path.read_bytes()
    cases = []

    def put(root, relative, data):
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def fixture(root):
        fixture_crate, fixture_owner = root / "crate", root / "owner"
        for label, data in captured.items():
            domain, relative = label.split("/", 1)
            put(fixture_crate if domain == "crate" else fixture_owner, relative, data)
        build = f"target/{dataset}/"
        put(fixture_crate, build + "surefire-reports/TEST-com.synexia.rewrite.M3TaskRecipeTest.xml",
            qualified["report"].read_bytes())
        for name in ("SDK_INPUTS.tsv", "SDK_RESULTS.tsv"):
            put(fixture_crate, build + "candidate/" + name, (qualified["candidate"] / name).read_bytes())
        for relative, data in qualified["outputs"].items():
            put(fixture_crate, build + "candidate/" + relative, data)
        target = root / "target"
        target.mkdir()
        for relative, data in qualified["preimages"].items():
            path = put(target, relative, data)
            path.chmod(0o751)
            os.utime(path, ns=(1_600_000_000_000_000_000, 1_600_000_000_000_000_000))
        sentinel = put(target, SENTINEL, b"unrelated bytes and modes must remain unchanged\n")
        os.utime(sentinel, ns=(1_600_000_000_000_000_001, 1_600_000_000_000_000_001))
        return fixture_crate, fixture_owner, target

    def invoke(label, fixture_crate, fixture_owner, target, *, check=False, expected_changes=None, refusal=None):
        arguments = [sys.executable, str(fixture_crate / "receive.py"), "--crate", str(fixture_crate),
                     "--recipe-owner", str(fixture_owner), "--dataset", dataset, "--target", str(target)]
        if check:
            arguments.append("--check")
        result = subprocess.run(arguments, capture_output=True, text=True, timeout=45,
                                env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"))
        (evidence / (label + ".stdout.log")).write_text(result.stdout, encoding="utf-8")
        (evidence / (label + ".stderr.log")).write_text(result.stderr, encoding="utf-8")
        if refusal is not None:
            if result.returncode == 0 or refusal not in result.stderr or "RECEIVER_PASS" in result.stdout:
                raise AssertionError(label + " failed expected refusal: " + result.stdout + result.stderr)
        else:
            if result.returncode != 0 or result.stderr or not result.stdout.startswith("M3_AST_BUILD_RECEIVER_PASS "):
                raise AssertionError(label + " failed: " + result.stdout + result.stderr)
            receipt = json.loads(result.stdout.split(" ", 1)[1])
            if receipt != {"dataset": dataset, "targets": count, "changes": expected_changes,
                           "mode": "check" if check else "apply", "actual_sdk_results": True}:
                raise AssertionError("incorrect receiver receipt: " + repr(receipt))
        cases.append({"case": label, "exit_code": result.returncode, "expected_refusal": refusal})

    try:
        with tempfile.TemporaryDirectory(prefix="isolated-", dir=output) as temporary:
            root = Path(temporary)
            fixture_crate, fixture_owner, target = fixture(root / "normal")
            initial = tree_state(target)
            invoke("check-no-write", fixture_crate, fixture_owner, target, check=True, expected_changes=changes)
            assert tree_state(target) == initial
            invoke("apply-exact-sdk", fixture_crate, fixture_owner, target, expected_changes=changes)
            for relative, data in qualified["outputs"].items():
                path = target / relative
                assert path.read_bytes() == data
                expected_mode = initial[relative]["mode"] if relative in initial else qualified["modes"][relative]
                assert stat.S_IMODE(path.stat().st_mode) == expected_mode
            assert tree_state(target)[SENTINEL] == initial[SENTINEL]
            applied = tree_state(target)
            invoke("fixed-point-no-write", fixture_crate, fixture_owner, target, expected_changes=0)
            assert tree_state(target) == applied

            late = sorted(qualified["outputs"])[-1]
            bad_crate, bad_owner, bad_target = fixture(root / "late-drift")
            put(bad_target, late, b"unadmitted source drift\n")
            initial = tree_state(bad_target)
            invoke("late-drift-no-partial-write", bad_crate, bad_owner, bad_target,
                   refusal="target source drift: " + late)
            assert tree_state(bad_target) == initial

            bad_crate, bad_owner, bad_target = fixture(root / "input-tamper")
            path = bad_crate / "datasets" / dataset / "MODES.tsv"
            path.write_bytes(path.read_bytes() + b"\n# altered after SDK qualification\n")
            initial = tree_state(bad_target)
            invoke("qualified-input-tamper", bad_crate, bad_owner, bad_target,
                   refusal="recipe changed after SDK qualification:")
            assert tree_state(bad_target) == initial

            bad_crate, bad_owner, bad_target = fixture(root / "result-tamper")
            path = bad_crate / "target" / dataset / "candidate" / late
            path.write_bytes(path.read_bytes() + b"\n// altered SDK Result\n")
            initial = tree_state(bad_target)
            invoke("sdk-result-tamper", bad_crate, bad_owner, bad_target,
                   refusal="SDK result changed after qualification: " + late)
            assert tree_state(bad_target) == initial

            bad_crate, bad_owner, bad_target = fixture(root / "symlink")
            link = bad_target / late
            link.parent.mkdir(parents=True, exist_ok=True)
            if link.exists():
                link.unlink()
            link.symlink_to(bad_target / SENTINEL)
            initial = tree_state(bad_target)
            invoke("symlink-target", bad_crate, bad_owner, bad_target,
                   refusal="symlink target or parent is not admitted:")
            assert tree_state(bad_target) == initial

            bad_crate, bad_owner, bad_target = fixture(root / "partial")
            for ordinal, (relative, data) in enumerate(sorted(qualified["outputs"].items())):
                if ordinal % 2 == 0:
                    put(bad_target, relative, data)
            partial_changes = sum(before != after and ordinal % 2 != 0
                                  for ordinal, (_, (before, after)) in enumerate(sorted(qualified["manifests"].items())))
            invoke("mixed-pre-postimages", bad_crate, bad_owner, bad_target, expected_changes=partial_changes)
            for relative, data in qualified["outputs"].items():
                assert (bad_target / relative).read_bytes() == data

            bad_crate, bad_owner, bad_target = fixture(root / "failed-tests")
            path = bad_crate / "target" / dataset / "surefire-reports/TEST-com.synexia.rewrite.M3TaskRecipeTest.xml"
            path.write_bytes(path.read_bytes().replace(b'failures="0"', b'failures="1"', 1))
            initial = tree_state(bad_target)
            invoke("failed-sdk-test-refusal", bad_crate, bad_owner, bad_target,
                   refusal="all actual SDK qualification tests must execute successfully")
            assert tree_state(bad_target) == initial

        for label, data in captured.items():
            domain, relative = label.split("/", 1)
            assert ((crate if domain == "crate" else owner) / relative).read_bytes() == data
        status = "PASS"
    except BaseException:
        status = "FAIL"
        raise
    finally:
        receipt = {"schema": "M3_AST_BUILD_RECEIVER_CONTRACT_V1", "status": status,
                   "dataset": dataset, "targets": count, "cases": cases,
                   "actual_sdk_results": True, "product_files_written": False,
                   "sdk_inputs_sha256": sha((qualified["candidate"] / "SDK_INPUTS.tsv").read_bytes()),
                   "sdk_results_sha256": sha((qualified["candidate"] / "SDK_RESULTS.tsv").read_bytes())}
        (evidence / "RECEIVER_RECEIPT.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print("M3_AST_BUILD_RECEIVER_CONTRACT_PASS cases=" + str(len(cases)) + " targets=" + str(count))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--crate", type=Path, required=True)
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--recipe-owner", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if sys.flags.optimize:
        parser.error("Python assertions are required")
    execute(args.crate.resolve(strict=True), args.dataset, args.recipe_owner.resolve(strict=True), args.output.absolute())


if __name__ == "__main__":
    main()
