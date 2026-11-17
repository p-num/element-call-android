# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
# Please see LICENSE files in the repository root for full details.

"""Guard the bare RTC release contract without resolving artifacts or running Gradle."""

from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
CORE_MODULE = 'includeModule("io.element.android", "matrix-rtc-android")'


def blocks(source, name):
    for match in re.finditer(rf"\b{re.escape(name)}\s*\{{", source):
        start = match.end()
        depth = 1
        for position in range(start, len(source)):
            depth += (source[position] == "{") - (source[position] == "}")
            if depth == 0:
                yield source[start:position]
                break


class RtcResolutionTests(unittest.TestCase):
    def test_both_builds_route_the_core_to_its_release_or_explicit_local_override(self):
        for path in ("settings.gradle.kts", "tests/consumer/settings.gradle.kts"):
            with self.subTest(path=path):
                source = (ROOT / path).read_text()
                repositories = [block for block in blocks(source, "exclusiveContent") if CORE_MODULE in block]
                self.assertEqual(len(repositories), 1)
                repository = repositories[0]
                self.assertIn('gradleProperty("matrixRtcLocalVersion")', source)
                self.assertRegex(repository, r"if\s*\([^\n]*matrixRtcLocalVersion[^\n]*\)")
                self.assertRegex(repository, r"mavenLocal\(\)\s*}\s*else\s*{")
                ivy = list(blocks(repository, "ivy"))
                self.assertEqual(len(ivy), 1)
                self.assertIn('uri("https://github.com/p-num/matrix-rust-rtc/releases/download")', ivy[0])
                self.assertIn('artifact("v[revision]/[artifact]-[revision].[ext]")', ivy[0])
                self.assertEqual([block.strip() for block in blocks(ivy[0], "metadataSources")], ["artifact()"])

    def test_consumer_core_source_is_independent_of_wrapper_distribution_mode(self):
        source = (ROOT / "tests/consumer/settings.gradle.kts").read_text()
        repositories = list(blocks(source, "repositories"))[-1]
        core_start = repositories.index("exclusiveContent")
        prefix = repositories[:core_start]
        self.assertEqual(prefix.count("{"), prefix.count("}"))

    def test_every_direct_core_dependency_preserves_the_aar_selector(self):
        modules = set()
        for path in (ROOT / "call").glob("*/build.gradle.kts"):
            for line in path.read_text().splitlines():
                if "libs.matrix.rtc.android" in line:
                    modules.add(path.parent.name)
                    self.assertRegex(line, r'(api|implementation)\(variantOf\(libs\.matrix\.rtc\.android\)\s*{ artifactType\("aar"\) }\)')
        self.assertEqual(modules, {"impl", "ui", "test"})


if __name__ == "__main__":
    unittest.main()
