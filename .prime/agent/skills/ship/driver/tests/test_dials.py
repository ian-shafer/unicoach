import fnmatch
import tempfile
import unittest

import fixtures
from fixtures import git
from ship_driver.dials import (IRREVERSIBLE_GLOBS, Dials, TestDepth, derive_approval, dial_line, parse_dial_line,
                                test_depth)
from ship_driver.run import ScriptFailed


class Approval(unittest.TestCase):
    def test_each_irreversible_path_class_requires_approval(self):
        for path in ("db/schema/0042.create-subscriptions.sql", "db/seed/automatic-awards/awards.csv",
                     "prompts/coach-system-prompt.md",
                     "service/src/main/kotlin/ed/unicoach/coaching/budget/BudgetService.kt",
                     "service/src/main/kotlin/ed/unicoach/subscriptions/SubscriptionPlans.kt",
                     "appstore/src/main/kotlin/ed/unicoach/appstore/AppStoreServerApi.kt",
                     "ios-app/UnicoachiOS.storekit", "ios-app/UnicoachiOS/StoreKitSubscriptionStore.swift",
                     "ios-app/UnicoachiOS/PaywallView.swift", "db/src/main/kotlin/ed/unicoach/db/models/Subscription.kt",
                     "admin-web/src/main/kotlin/ed/unicoach/admin/auth/AdminAuth.kt", "PRODUCT.md",
                     "public-web/src/main/kotlin/ed/unicoach/web/render/HomePage.kt"):
            self.assertEqual(derive_approval("none", [path])[0], "required", path)

    def test_every_irreversible_glob_matches_a_tracked_path(self):
        tracked = git(fixtures.REPO_ROOT, "ls-files").splitlines()
        stale = [glob for glob in IRREVERSIBLE_GLOBS if not any(fnmatch.fnmatchcase(p, glob) for p in tracked)]
        self.assertEqual(stale, [], "a glob that matches nothing protects nothing; point it at the real paths")

    def test_design_rfc_requires_approval(self):
        self.assertEqual(derive_approval("rfc", []), ("required", "design=rfc"))

    def test_a_service_test_only_diff_skips_approval(self):
        self.assertEqual(derive_approval("none", ["service/src/test/kotlin/ed/unicoach/FooTest.kt"])[0], "skipped")

    def test_the_instruction_alone_can_require_it(self):
        self.assertEqual(derive_approval("none", [], "add a migration for the college table")[0], "required")
        self.assertEqual(derive_approval("none", [], "build data deletion for accounts")[0], "required")

    def test_dials_refuse_an_unknown_value(self):
        with self.assertRaises(ValueError):
            Dials("lane-a", "full")

    def test_dials_refuse_narrowing_outside_targeted_scrutiny(self):
        for scrutiny in ("none", "full"):
            with self.assertRaisesRegex(ValueError, "narrow applies only to scrutiny='targeted'"):
                Dials("none", scrutiny, narrow=True)
        self.assertTrue(Dials("none", "targeted", narrow=True).narrow)


class GateScope(unittest.IsolatedAsyncioTestCase):
    async def test_gate_scope_runs_over_the_base_to_head_set_and_leaves_the_index_alone(self):
        repo = fixtures.make_repo(tempfile.mkdtemp())
        base = git(repo, "rev-parse", "HEAD").strip()
        (repo / "notes.md").write_text("a note\n")
        git(repo, "add", "notes.md")
        git(repo, "commit", "-q", "--no-verify", "-m", "note")
        (repo / "staged.md").write_text("staged, not committed\n")
        git(repo, "add", "staged.md")
        before = git(repo, "diff", "--cached", "--name-only")
        tests = await test_depth(repo, base, gate_scope=fixtures.REPO_ROOT / "bin" / "gate-scope")
        self.assertEqual(tests, TestDepth("markdown-docs", gradle=False, every_harness=False))
        self.assertEqual(git(repo, "diff", "--cached", "--name-only"), before)

    async def gate_scope_says(self, *lines):
        repo = fixtures.make_repo(tempfile.mkdtemp())
        stub = repo / "stub-gate-scope"
        fixtures.write_exec(stub, "".join(f"echo '{line}'\n" for line in lines))
        return await test_depth(repo, "HEAD", gate_scope=stub)

    async def test_gate_scope_output_lacking_a_key_or_a_yes_no_gradle_is_refused_with_the_output(self):
        with self.assertRaisesRegex(ScriptFailed, "(?s)harnesses.*scope=full"):
            await self.gate_scope_says("scope=full", "gradle=yes")
        with self.assertRaisesRegex(ScriptFailed, "(?s)gradle=maybe"):
            await self.gate_scope_says("scope=full", "gradle=maybe", "harnesses=")

    async def test_harnesses_all_is_every_harness_never_a_name(self):
        depth = await self.gate_scope_says("scope=full", "gradle=yes", "harnesses=ALL")
        self.assertEqual(depth, TestDepth("full", gradle=True, every_harness=True))

    def test_the_dial_line_is_one_shell_word_and_round_trips(self):
        for depth in (TestDepth("prime-scripts", False, False, ("ship-scripts-tests", "docs-scripts-tests")),
                      TestDepth("full", True, True)):
            line = dial_line(depth)
            self.assertNotIn(" ", line)
            self.assertEqual(parse_dial_line(line), depth)

    def test_a_dial_line_lacking_a_key_or_with_an_unknown_gradle_value_is_refused(self):
        for line in ("", "scope=full,gradle=yes", "scope=full,gradle=maybe,harnesses="):
            with self.assertRaisesRegex(ValueError, "DIAL_TESTS", msg=line):
                parse_dial_line(line)
