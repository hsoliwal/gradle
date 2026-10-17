#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
"""Seal task inputs for the existing Maven/OpenRewrite snapshot recipes; never write product targets."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import tempfile

TASK = "m3-ast-build-reverse-20261017"
RECIPE = "com.synexia.m3jdk.AstBuildReverse20261017"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def relative_path(value):
    path = PurePosixPath(value)
    if (not value or str(path) != value or path.is_absolute() or ".." in path.parts or ".git" in path.parts
            or "\\" in value or any(c in value for c in "\t\r\n\0")):
        raise ValueError("unsafe repository-relative path: " + repr(value))
    return path


def source_path(root, relative):
    relative_path(relative)
    path = root / relative
    cursor = path
    while cursor != root:
        if cursor.is_symlink():
            raise ValueError("symlink source or parent: " + relative)
        cursor = cursor.parent
    if path.exists() and not path.is_file():
        raise ValueError("source is not a regular file: " + relative)
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError("source escapes root: " + relative)
    return path


def text_bytes(path):
    data = path.read_bytes()
    data.decode("utf-8", errors="strict")
    if data.startswith(b"\xef\xbb\xbf") or b"\0" in data:
        raise ValueError("task source must be UTF-8 without BOM or NUL: " + str(path))
    return data


def collect(baseline, roots):
    targets = {}
    for root in roots:
        if root.is_symlink() or not root.is_dir():
            raise ValueError("candidate root must be a real directory")
        for directory, directories, files in os.walk(root, followlinks=False):
            parent = Path(directory)
            for name in directories:
                if (parent / name).is_symlink():
                    raise ValueError("symlink candidate directory")
            for name in files:
                path = parent / name
                relative = path.relative_to(root).as_posix()
                source_path(root, relative)
                if relative in targets:
                    raise ValueError("overlapping candidate ownership: " + relative)
                before_path = source_path(baseline, relative)
                before = text_bytes(before_path) if before_path.exists() else None
                after = text_bytes(path)
                mode = stat.S_IMODE(before_path.stat().st_mode) if before is not None else stat.S_IMODE(path.stat().st_mode)
                if mode & ~0o777:
                    raise ValueError("unexpected source mode: " + relative)
                targets[relative] = (before, after, mode)
    if not targets:
        raise ValueError("at least one candidate source is required")
    return dict(sorted(targets.items()))


def seal(crate, dataset, repository, baseline, roots, baseline_ref=None, recipe_owner=None):
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,31}", dataset):
        raise ValueError("invalid dataset name")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("repository must use owner/name")
    if not baseline.is_dir() or baseline.is_symlink():
        raise ValueError("baseline must be an existing real directory")
    targets = collect(baseline, roots)
    datasets = crate / "datasets"
    datasets.mkdir(parents=True, exist_ok=True)
    destination = datasets / dataset
    if destination.is_symlink():
        raise ValueError("dataset destination is a symlink")
    with tempfile.TemporaryDirectory(prefix=".prepare-", dir=datasets) as temporary:
        staging = Path(temporary)
        resources = staging / "resources"
        rows = {"java": [], "text": []}
        modes = []
        crate_name = TASK + "-" + dataset
        for ordinal, (relative, (before, after, mode)) in enumerate(targets.items(), 1):
            kind = "java" if relative.endswith(".java") else "text"
            lane = resources / ("com/synexia/rewrite/hash-pinned-" + kind) / crate_name
            lane.mkdir(parents=True, exist_ok=True)
            template = f"{ordinal:04d}-{Path(relative).name}.txt"
            (lane / template).write_bytes(after)
            if before is not None:
                (lane / "pre").mkdir(exist_ok=True)
                (lane / "pre" / template).write_bytes(before)
            rows[kind].append("\t".join((relative, "ABSENT" if before is None else sha(before), sha(after), template)))
            modes.append(relative + "\t" + format(mode, "04o"))
        active = []
        for kind, records in rows.items():
            if not records:
                continue
            lane = resources / ("com/synexia/rewrite/hash-pinned-" + kind) / crate_name
            (lane / "manifest.tsv").write_text("\n".join(records) + "\n", encoding="utf-8")
            active.append("  - com.synexia.rewrite.M3HashPinned" + kind.title() + "SnapshotRecipe:\n      crateName: " + crate_name)
        descriptor = resources / "META-INF/rewrite" / (crate_name + ".yml")
        descriptor.parent.mkdir(parents=True, exist_ok=True)
        descriptor.write_text("type: specs.openrewrite.org/v1beta/recipe\nname: " + RECIPE
                              + "\ndisplayName: M3 shared AST and optional build integration\n"
                              + "description: Replays the sealed task through the existing canonical snapshot owners.\n"
                              + "recipeList:\n" + "\n".join(active) + "\n", encoding="utf-8")
        (staging / "MODES.tsv").write_text("\n".join(modes) + "\n", encoding="utf-8")
        metadata = {"schema": "M3_AST_BUILD_REVERSE_DATASET_V1", "dataset": dataset,
                    "repository": repository, "baseline_ref": baseline_ref,
                    "crate_name": crate_name, "recipe_name": RECIPE,
                    "targets": len(targets), "java_targets": len(rows["java"]),
                    "text_targets": len(rows["text"]), "product_sources_written": False}
        origins = crate / "ENGINE_SOURCES.tsv"
        if origins.exists():
            effective = []
            changed = []
            for line in origins.read_text(encoding="utf-8").splitlines():
                if not line or line.startswith("#"):
                    continue
                source, blob, source_hash, license_name, url = line.split("\t")
                relative = source.removeprefix("synexia-openrewrite-recipes/")
                if recipe_owner is not None:
                    owner_bytes = text_bytes(source_path(recipe_owner, relative))
                    current_hash = sha(owner_bytes)
                    current_blob = hashlib.sha1(b"blob " + str(len(owner_bytes)).encode("ascii") + b"\0" + owner_bytes).hexdigest()
                    if current_hash != source_hash:
                        changed.append(source)
                else:
                    current_hash, current_blob = source_hash, blob
                effective.append("\t".join((relative, current_blob, current_hash)))
            (staging / "ENGINE_EFFECTIVE.tsv").write_text("# Compiled owner bytes; immutable origins remain in root ENGINE_SOURCES.tsv\n"
                                                        + "\n".join(effective) + "\n", encoding="utf-8")
            metadata["engine_changes_from_origin"] = changed
        (staging / "DATASET.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        # Only task-owned input resources are replaced. Product files are never modified here.
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(staging, destination)
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--crate", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidates", type=Path, action="append", required=True)
    parser.add_argument("--baseline-ref", help="Pinned receiving commit; optional for qualification fixtures")
    parser.add_argument("--recipe-owner", type=Path, help="Effective canonical owner source root, after qualified bootstrap if needed")
    args = parser.parse_args()
    print(json.dumps(seal(args.crate.absolute(), args.dataset, args.repository,
                          args.baseline.absolute(), [p.absolute() for p in args.candidates], args.baseline_ref,
                          args.recipe_owner.absolute() if args.recipe_owner else None), sort_keys=True))


if __name__ == "__main__":
    main()
