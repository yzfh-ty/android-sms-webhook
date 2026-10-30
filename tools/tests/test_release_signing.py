import base64
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("release_signing", Path(__file__).resolve().parents[1] / "release_signing.py")
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class SigningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = Path(__file__).resolve().parents[2]
        cls.temporary_root = (root / "build/signing-test-tmp").resolve()
        if not cls.temporary_root.is_relative_to(root):
            raise ValueError("Test directory must remain in the workspace")
        cls.temporary_root.mkdir(parents=True, exist_ok=True)

    def configuration(self):
        return {"keystore_base64": base64.b64encode(b"keystore fixture").decode(),
            "store_password": "fixture-store-password", "key_alias": "release", "key_password": "fixture-key-password"}

    def test_missing_or_malformed_configuration_is_rejected_without_disclosing_values(self):
        for value in ("", "null", "[]", "private-value-is-not-json", "{}"):
            with self.subTest(value=value), self.assertRaises(ValueError) as error:
                signing.parse_configuration(value)
            self.assertNotIn("private-value-is-not-json", str(error.exception))

    def test_invalid_base64_is_rejected(self):
        value = self.configuration()
        value["keystore_base64"] = "not base64!"
        with self.assertRaises(ValueError):
            signing.parse_configuration(json.dumps(value))

    def test_missing_fields_and_environment_injection_are_rejected(self):
        for key in self.configuration():
            for bad in ("", None, "value\nINJECTED=true", "value\r", "value\0"):
                value = self.configuration()
                value[key] = bad
                with self.subTest(key=key, bad=bad), self.assertRaises(ValueError):
                    signing.parse_configuration(json.dumps(value))

    def test_prepare_writes_only_expected_environment_fields(self):
        with tempfile.TemporaryDirectory(dir=self.temporary_root) as directory:
            root = Path(directory)
            values = signing.prepare_environment(json.dumps(self.configuration()), root, root / "environment")
            self.assertEqual(b"keystore fixture", (root / "android-release.p12").read_bytes())
            self.assertEqual({"ANDROID_KEYSTORE_PATH", "ANDROID_STORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD"}, set(values))
            self.assertEqual(4, len((root / "environment").read_text().splitlines()))

    def test_certificate_output_requires_one_signer(self):
        digest = "ab" * 32
        line = "Signer #1 certificate SHA-256 digest: " + digest
        self.assertEqual(digest, signing.certificate_digest(line))
        for output in ("", "DOES NOT VERIFY", line + "\nSigner #2 certificate SHA-256 digest: " + digest):
            with self.assertRaises(ValueError):
                signing.certificate_digest(output)

    def fixture(self, root):
        (root / ".github").mkdir()
        (root / ".github/release-signing.sha256").write_text("ab" * 32)
        output = root / "app/build/outputs/apk/release"
        output.mkdir(parents=True)
        metadata = {"elements": [{"outputFile": f"app-{abi}-release.apk"} for abi in ("arm64-v8a", "x86_64")]}
        (output / "output-metadata.json").write_text(json.dumps(metadata))

    def test_both_apks_must_verify_with_the_pinned_certificate(self):
        with tempfile.TemporaryDirectory(dir=self.temporary_root) as directory:
            root = Path(directory)
            self.fixture(root)
            valid = subprocess.CompletedProcess([], 0, "Signer #1 certificate SHA-256 digest: " + "ab" * 32, "")
            with patch.object(signing.subprocess, "run", return_value=valid) as process:
                self.assertEqual(["ab" * 32] * 2, signing.verify_apks(root, "apksigner"))
                self.assertEqual(2, process.call_count)
            for invalid in (subprocess.CompletedProcess([], 1, "", "unsigned"),
                subprocess.CompletedProcess([], 0, "Signer #1 certificate SHA-256 digest: " + "cd" * 32, "")):
                with patch.object(signing.subprocess, "run", return_value=invalid), self.assertRaises(ValueError):
                    signing.verify_apks(root, "apksigner")


if __name__ == "__main__":
    unittest.main()
