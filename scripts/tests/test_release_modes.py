# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
# Please see LICENSE files in the repository root for full details.

"""Exercise release mode selection without running Gradle or contacting GitHub."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "release.sh"


class ReleaseModeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.repo = self.base / "repo"
        (self.repo / "scripts").mkdir(parents=True)
        shutil.copy2(SCRIPT, self.repo / "scripts/release.sh")
        (self.repo / "gradle.properties").write_text("VERSION_NAME=0.1.0\n")
        (self.repo / "CHANGES.md").write_text("# Changes\n\n## Unreleased\n\n")
        (self.repo / ".gitignore").write_text("build/\nrelease-notes.md\nrtc/local/\n")
        (self.repo / "rtc/local").mkdir(parents=True)
        (self.repo / "rtc/local/matrixrtc-release.aar").write_bytes(b"fixture")
        tools = self.base / "bin"
        tools.mkdir()
        (tools / "gh").write_text("#!/bin/sh\nprintf '* Native call changes (#1)\\n'\n")
        (self.repo / "gradlew").write_text(
            "#!/usr/bin/env python3\n"
            "import json, os, pathlib, sys\n"
            "with open(os.environ['COMMAND_LOG'], 'a') as log: log.write(json.dumps(sys.argv[1:]) + '\\n')\n"
            "if 'publishAllPublicationsToDistRepository' in sys.argv:\n"
            " version = pathlib.Path('gradle.properties').read_text().strip().split('=')[1]\n"
            " artifact = pathlib.Path('build/dist/io/element/android/element-call') / version / f'element-call-{version}.aar'\n"
            " artifact.parent.mkdir(parents=True, exist_ok=True)\n"
            " artifact.write_bytes(b'fixture')\n"
        )
        (tools / "gh").chmod(0o755)
        (self.repo / "gradlew").chmod(0o755)
        self.git("init", "--quiet")
        self.git("add", ".")
        self.git("-c", "user.name=Release tests", "-c", "user.email=release-tests@example.invalid", "commit", "--quiet", "-m", "Fixture")
        self.revision = self.git("rev-parse", "HEAD").strip()
        self.log = self.base / "commands.jsonl"
        self.env = dict(os.environ, PATH=f"{tools}:{os.environ['PATH']}", COMMAND_LOG=str(self.log), GH_REPO="p-num/element-call-android")
        self.env.pop("GITHUB_ACTIONS", None)
        self.env.pop("GITHUB_OUTPUT", None)
        self.env.pop("GITHUB_REPOSITORY", None)

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, text=True, stderr=subprocess.STDOUT)

    def run_release(self, *args, **env):
        return subprocess.run(
            ["bash", "scripts/release.sh", "0.2.0-letro.1", *args],
            cwd=self.repo, env=dict(self.env, **env), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )

    def commands(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_ci_refuses_test_and_quality_execution(self):
        result = self.run_release(GITHUB_ACTIONS="true")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("CI may build release packages only", result.stdout)
        self.assertEqual(self.commands(), [])

    def test_package_only_requires_the_validated_revision(self):
        result = self.run_release("--package-only", VALIDATED_SOURCE_REVISION="0" * 40)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("differs from the locally validated commit", result.stdout)
        self.assertEqual(self.commands(), [])

    def test_package_only_refuses_dirty_validated_source(self):
        (self.repo / "gradle.properties").write_text("VERSION_NAME=unvalidated\n")
        result = self.run_release("--package-only", VALIDATED_SOURCE_REVISION=self.revision)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("clean locally validated checkout", result.stdout)
        self.assertEqual(self.commands(), [])

    def test_ci_builds_and_packages_without_tests(self):
        result = self.run_release("--package-only", GITHUB_ACTIONS="true", VALIDATED_SOURCE_REVISION=self.revision)
        self.assertEqual(result.returncode, 0, result.stdout)
        commands = self.commands()
        self.assertEqual(commands[0], ["assemble"])
        self.assertFalse(any("build" in command or "runQualityChecks" in command or any("test" in arg.lower() and arg != "tests/consumer" for arg in command) for command in commands))
        self.assertTrue((self.repo / "build/release-assets/SHA256SUMS").exists())

    def test_local_rehearsal_preserves_the_validation_gate(self):
        result = self.run_release()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(self.commands()[0], ["build", "runQualityChecks"])


if __name__ == "__main__":
    unittest.main()
