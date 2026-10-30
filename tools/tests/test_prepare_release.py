import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

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

    def write_fixture(self, root):
        output = root / "app/build/outputs/apk/release"
        output.mkdir(parents=True)
        metadata = {"applicationId": "com.tyverse.smswebhook", "variantName": "release", "elements": []}
        for abi in ("arm64-v8a", "x86_64"):
            filename = f"app-{abi}-release.apk"
            with zipfile.ZipFile(output / filename, "w") as apk:
                apk.writestr(f"lib/{abi}/fixture.so", b"native fixture")
            metadata["elements"].append({"outputFile": filename, "versionName": "0.3.3",
                "filters": [{"filterType": "ABI", "value": abi}]})
        (output / "output-metadata.json").write_text(json.dumps(metadata))
        return output, metadata

    def package_fixture(self, publish):
        with tempfile.TemporaryDirectory(dir=self.temporary_root) as directory:
            root = Path(directory)
            self.write_fixture(root)
            artifacts = release.package_apks(root, {"publish": publish, "tag": "v0.3.3", "version": "0.3.3"})
            self.assertEqual(2, len(artifacts))
            self.assertEqual(["android-sms-webhook-v0.3.3-arm64-v8a.apk",
                "android-sms-webhook-v0.3.3-x86_64.apk"], [path.name for path in artifacts])
            self.assertEqual(2, len((root / "dist/SHA256SUMS.txt").read_text().splitlines()))

    def test_build_only_packages_two_abis_and_checksums(self):
        self.package_fixture("false")

    def test_publishing_packages_signed_release_filenames(self):
        self.package_fixture("true")

    def assert_invalid_metadata(self, edit):
        with tempfile.TemporaryDirectory(dir=self.temporary_root) as directory:
            root = Path(directory)
            output, metadata = self.write_fixture(root)
            edit(metadata)
            (output / "output-metadata.json").write_text(json.dumps(metadata))
            with self.assertRaises(ValueError):
                release.package_apks(root, {"publish": "true", "tag": "v0.3.3", "version": "0.3.3"})

    def test_qa_apk_cannot_be_published_as_normal_app(self):
        self.assert_invalid_metadata(lambda m: m.update(applicationId="com.tyverse.smswebhook.qa"))

    def test_debug_variant_cannot_be_published(self):
        self.assert_invalid_metadata(lambda m: m.update(variantName="debug"))

    def test_missing_or_extra_apks_are_rejected(self):
        self.assert_invalid_metadata(lambda m: m["elements"].pop())
        self.assert_invalid_metadata(lambda m: m["elements"].append(m["elements"][0].copy()))

    def test_universal_apk_is_rejected(self):
        self.assert_invalid_metadata(lambda m: m["elements"][0].update(filters=[]))

    def test_duplicate_abis_are_rejected(self):
        self.assert_invalid_metadata(lambda m: m["elements"][1].update(filters=[{"filterType": "ABI", "value": "arm64-v8a"}]))

    def test_wrong_version_or_unsigned_filename_are_rejected(self):
        self.assert_invalid_metadata(lambda m: m["elements"][0].update(versionName="0.3.1"))
        self.assert_invalid_metadata(lambda m: m["elements"][0].update(outputFile="app-arm64-v8a-release-unsigned.apk"))

    def test_mislabeled_native_libraries_are_rejected(self):
        with tempfile.TemporaryDirectory(dir=self.temporary_root) as directory:
            root = Path(directory)
            output, _ = self.write_fixture(root)
            with zipfile.ZipFile(output / "app-arm64-v8a-release.apk", "w") as apk:
                apk.writestr("lib/x86_64/fixture.so", b"wrong ABI")
            with self.assertRaises(ValueError):
                release.package_apks(root, {"publish": "true", "tag": "v0.3.3", "version": "0.3.3"})


if __name__ == "__main__":
    unittest.main()
