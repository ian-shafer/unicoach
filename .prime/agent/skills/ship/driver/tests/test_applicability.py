import re
import tempfile
import unittest
from pathlib import Path

import fixtures
from ship_driver.applicability import LENS_GROUP, allowed, classify, skip_reason

TIERS_MD = fixtures.REPO_ROOT / ".claude" / "skills" / "review-fix" / "tiers.md"


class Applicability(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())

    def test_every_lens_in_the_tier_manifest_has_a_row(self):
        lenses = set(re.findall(r"^\|\s*`((?:code|design|impl)-review-[a-z-]+)`", TIERS_MD.read_text(), re.M))
        self.assertGreater(len(lenses), 30)
        self.assertEqual(lenses - set(LENS_GROUP), set())

    def test_a_kotlin_only_diff_allows_the_typed_language_group(self):
        classes = classify(["service/src/main/kotlin/A.kt"], self.root)
        self.assertEqual(classes, {"kotlin"})
        self.assertTrue(allowed("code-review-immutable-returns", classes, "none"))
        self.assertTrue(allowed("code-review-dry-abstractions", classes, "none"))
        self.assertFalse(allowed("code-review-semantic-output", classes, "none"))
        self.assertIsNone(skip_reason("code-review-dry-abstractions", classes, "none"))

    def test_a_shell_only_diff_allows_semantic_output_and_not_the_typed_group(self):
        script = self.root / "bin" / "thing"
        script.parent.mkdir()
        script.write_text("#!/usr/bin/env bash\necho hi\n")
        classes = classify(["bin/thing"], self.root)
        self.assertEqual(classes, {"shell"})
        self.assertTrue(allowed("code-review-semantic-output", classes, "none"))
        self.assertFalse(allowed("code-review-execution-context", classes, "none"))
        self.assertFalse(allowed("code-review-immutable-returns", classes, "none"))

    def test_a_path_that_cannot_be_read_for_another_reason_than_being_deleted_is_not_classified(self):
        (self.root / "a-directory").mkdir()
        with self.assertRaises(IsADirectoryError):
            classify(["a-directory"], self.root)
        self.assertEqual(classify(["deleted-by-this-diff"], self.root), {"config"})

    def test_a_markdown_only_diff_allows_nothing(self):
        classes = classify(["rfc/1-x.md", "prompts/coach-system-prompt.md"], self.root)
        self.assertEqual(classes, {"markdown", "prompt"})
        self.assertEqual([lens for lens in LENS_GROUP if allowed(lens, classes, "rfc")], [])

    def test_impl_review_needs_an_rfc(self):
        classes = frozenset({"kotlin"})
        self.assertFalse(allowed("impl-review-plan-followed", classes, "none"))
        self.assertTrue(allowed("impl-review-plan-followed", classes, "rfc"))
        self.assertEqual(skip_reason("impl-review-plan-followed", classes, "none"), "no RFC in this run")

    def test_the_shared_skip_reason_names_the_classes_present(self):
        reason = skip_reason("code-review-no-sentinels", frozenset({"shell", "markdown"}), "none")
        self.assertIn("markdown, shell", reason)
        self.assertEqual(reason, skip_reason("code-review-no-leaks", frozenset({"shell", "markdown"}), "none"))
