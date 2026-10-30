import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("prepare_release", Path(__file__).resolve().parents[1] / "prepare_release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        workspace = Path(__file__).resolve().parents[2]
        cls.temporary_root = (workspace / "build/release-test-tmp").resolve()
        if not cls.temporary_root.is_relative_to(workspace):
            raise ValueError("Test directory must stay inside the workspace")
        cls.temporary_root.mkdir(parents=True, exist_ok=True)

    def test_manual_build_does_not_publish(self):
        plan = release.release_plan('versionName = "0.3.1"', {})
        self.assertEqual("false", plan["publish"])
        self.assertEqual("v0.3.1", plan["tag"])

    def test_tag_release_and_prerelease(self):
        plan = release.release_plan('versionName = "0.3.1-rc.1"', {
            "GITHUB_REF_TYPE": "tag", "GITHUB_REF_NAME": "v0.3.1-rc.1", "PUBLISH_RELEASE": "true"})
        self.assertEqual("true", plan["publish"])
        self.assertEqual("true", plan["prerelease"])

    def test_mismatched_or_unsafe_tags_are_rejected(self):
        for tag in ["v0.3.0", "v0.3.1\nunsafe", "$(echo unsafe)"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.release_plan('versionName = "0.3.1"', {"RELEASE_TAG": tag})

    def test_invalid_versions_are_rejected(self):
        for source in ["", 'versionName = "latest"', 'versionName = "0.3.1/path"']:
            with self.subTest(source=source), self.assertRaises(ValueError):
                release.release_plan(source, {})

    def test_publishing_unsigned_apk_is_rejected(self):
        with tempfile.TemporaryDirectory(dir=self.temporary_root) as directory, self.assertRaises(ValueError):
            release.package_apks(Path(directory), {"publish": "true"}, signed=False)

    def test_build_only_labels_unsigned_artifacts_and_produces_checksums(self):
        with tempfile.TemporaryDirectory(dir=self.temporary_root) as directory:
            root = Path(directory)
            for variant in ["debug", "release"]:
                output = root / "app/build/outputs/apk" / variant
                output.mkdir(parents=True)
                (output / "app.apk").write_bytes(f"fixture-{variant}".encode())
                (output / "output-metadata.json").write_text(json.dumps({"applicationId": "com.tyverse.smswebhook",
                    "elements": [{"outputFile": "app.apk", "versionName": "0.3.1"}]}))
            artifacts = release.package_apks(root, {"publish": "false", "tag": "v0.3.1", "version": "0.3.1"}, signed=False)
            self.assertEqual(2, len(artifacts))
            self.assertTrue(artifacts[1].name.endswith("release-unsigned.apk"))
            self.assertEqual(2, len((root / "dist/SHA256SUMS.txt").read_text().splitlines()))

    def test_qa_apk_cannot_be_published_as_normal_app(self):
        with tempfile.TemporaryDirectory(dir=self.temporary_root) as directory:
            root = Path(directory)
            output = root / "app/build/outputs/apk/release"
            output.mkdir(parents=True)
            (output / "output-metadata.json").write_text(json.dumps({"applicationId": "com.tyverse.smswebhook.qa",
                "elements": [{"outputFile": "app.apk", "versionName": "0.3.1"}]}))
            with self.assertRaises(ValueError):
                release.package_apks(root, {"publish": "true", "tag": "v0.3.1", "version": "0.3.1"}, signed=True)


if __name__ == "__main__":
    unittest.main()
