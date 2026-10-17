#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
"""Task-specific input sealing contracts, including byte fidelity and refusal before mutation."""

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
CRATE = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("m3_ast_build_prepare", CRATE / "prepare.py")
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


class PrepareContract(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="prepare-proof-")
        self.root = Path(self.temporary.name)
        self.baseline = self.root / "baseline"
        self.proposed = self.root / "proposed"
        self.crate = self.root / "crate"
        self.baseline.mkdir()
        self.proposed.mkdir()
        self.crate.mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    @staticmethod
    def put(root, path, data):
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        return target

    def seal(self, roots=None):
        return PREPARE.seal(self.crate, "fixture", "hsoliwal/M3JDK21", self.baseline,
                            roots or [self.proposed], "fixture-baseline")

    def test_sorted_lanes_crlf_bytes_and_absent_admission(self):
        before = b"class A { int value() { return 1; } }\r\n"
        after = b"class A { int value() { return 2; } }\r\n"
        self.put(self.baseline, "src/A.java", before)
        self.put(self.proposed, "src/A.java", after)
        script = self.put(self.proposed, "tools/build.py", b"print('ready')\n")
        script.chmod(0o755)
        metadata = self.seal()
        self.assertEqual((2, 1, 1), (metadata["targets"], metadata["java_targets"], metadata["text_targets"]))
        self.assertEqual(before, (self.baseline / "src/A.java").read_bytes())
        self.assertFalse((self.baseline / "tools/build.py").exists())
        resource = self.crate / "datasets/fixture/resources/com/synexia/rewrite"
        java = resource / "hash-pinned-java/m3-ast-build-reverse-20261017-fixture"
        self.assertEqual(after, (java / "0001-A.java.txt").read_bytes())
        self.assertEqual(before, (java / "pre/0001-A.java.txt").read_bytes())
        text = resource / "hash-pinned-text/m3-ast-build-reverse-20261017-fixture/manifest.tsv"
        self.assertIn("\tABSENT\t", text.read_text())
        self.assertIn("tools/build.py\t0755", (self.crate / "datasets/fixture/MODES.tsv").read_text())

    def test_resealing_is_reproducible(self):
        self.put(self.proposed, "build.gradle.kts", b"plugins { java }\n")
        self.seal()
        dataset = self.crate / "datasets/fixture"
        first = {p.relative_to(dataset): p.read_bytes() for p in dataset.rglob("*") if p.is_file()}
        self.seal()
        second = {p.relative_to(dataset): p.read_bytes() for p in dataset.rglob("*") if p.is_file()}
        self.assertEqual(first, second)

    def test_overlapping_ownership_refuses_without_replacing_seal(self):
        self.put(self.proposed, "pom.xml", b"<project/>\n")
        self.seal()
        second = self.root / "second"
        second.mkdir()
        self.put(second, "pom.xml", b"<project>duplicate</project>\n")
        metadata = (self.crate / "datasets/fixture/DATASET.json").read_bytes()
        with self.assertRaisesRegex(ValueError, "overlapping"):
            self.seal([self.proposed, second])
        self.assertEqual(metadata, (self.crate / "datasets/fixture/DATASET.json").read_bytes())

    def test_symlink_candidate_file_refuses(self):
        source = self.put(self.root, "external.py", b"print('external')\n")
        (self.proposed / "build.py").symlink_to(source)
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.seal()
        self.assertFalse((self.crate / "datasets").exists())

    def test_symlink_candidate_parent_refuses(self):
        (self.proposed / "external").symlink_to(self.baseline, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.seal()

    def test_symlink_baseline_source_refuses(self):
        self.put(self.proposed, "build.xml", b"<project/>\n")
        source = self.put(self.root, "external.xml", b"<project/>\n")
        (self.baseline / "build.xml").symlink_to(source)
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.seal()

    def test_empty_and_invalid_text_refuse(self):
        with self.assertRaisesRegex(ValueError, "at least one"):
            self.seal()
        target = self.put(self.proposed, "input.bin", b"\xff\0")
        with self.assertRaises(UnicodeDecodeError):
            self.seal()
        target.write_bytes(b"\xef\xbb\xbftext")
        with self.assertRaisesRegex(ValueError, "BOM"):
            self.seal()

    def test_unsafe_repository_paths_refuse(self):
        for path in ("../outside", "/outside", "a/../b", "a//b", "a\\b", ".git/config", "a\tb"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                PREPARE.relative_path(path)


if __name__ == "__main__":
    unittest.main()
