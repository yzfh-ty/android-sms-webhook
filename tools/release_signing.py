"""Prepare CI signing material and verify Release APKs against the pinned public certificate."""
import base64
import binascii
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def parse_configuration(raw: str) -> dict:
    try:
        data = json.loads(raw)
    except (TypeError, ValueError):
        raise ValueError("Release signing configuration is missing or invalid") from None
    if not isinstance(data, dict):
        raise ValueError("Release signing configuration must be an object")
    keys = ("keystore_base64", "store_password", "key_alias", "key_password")
    for key in keys:
        value = data.get(key)
        if not isinstance(value, str) or not value or any(char in value for char in ("\r", "\n", "\0")):
            raise ValueError("Release signing configuration contains a missing or invalid field")
    try:
        keystore = base64.b64decode(data["keystore_base64"], validate=True)
    except (ValueError, binascii.Error):
        raise ValueError("Release keystore is not valid Base64") from None
    if not 1 <= len(keystore) <= 48_000:
        raise ValueError("Release keystore size is invalid")
    return {**data, "keystore_bytes": keystore}


def prepare_environment(raw: str, runner_temp: Path, environment_file: Path) -> dict:
    config = parse_configuration(raw)
    path = runner_temp.resolve() / "android-release.p12"
    if any(char in str(path) for char in ("\r", "\n", "\0")):
        raise ValueError("Runner signing directory is invalid")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "wb") as keystore:
        keystore.write(config["keystore_bytes"])
    values = {"ANDROID_KEYSTORE_PATH": str(path), "ANDROID_STORE_PASSWORD": config["store_password"],
              "ANDROID_KEY_ALIAS": config["key_alias"], "ANDROID_KEY_PASSWORD": config["key_password"]}
    with environment_file.open("a", encoding="utf-8", newline="\n") as output:
        output.write("".join(f"{key}={value}\n" for key, value in values.items()))
    return values


def certificate_digest(output: str) -> str:
    certificates = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", output, re.MULTILINE)
    if len(certificates) != 1:
        raise ValueError("Expected exactly one verified APK signer")
    return certificates[0].lower()


def verify_apks(root: Path, apksigner: str) -> list:
    fingerprint = (root / ".github/release-signing.sha256").read_text(encoding="ascii").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
        raise ValueError("Release certificate pin is invalid")
    directory = root / "app/build/outputs/apk/release"
    metadata = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
    certificates = []
    for element in metadata["elements"]:
        filename = element["outputFile"]
        if not re.fullmatch(r"app-(arm64-v8a|x86_64)-release\.apk", filename):
            raise ValueError("Expected signed ABI-specific Release APKs")
        command = [apksigner, "verify", "--verbose", "--print-certs", str(directory / filename)]
        # Invoke the same SDK signer directly on Windows, avoiding batch quoting and paths with spaces.
        if os.name == "nt" and apksigner.lower().endswith((".bat", ".cmd")):
            command = ["java", "-jar", str(Path(apksigner).parent / "lib/apksigner.jar"), *command[1:]]
        result = subprocess.run(command, capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise ValueError("APK signature verification failed: " + filename)
        digest = certificate_digest(result.stdout)
        if digest != fingerprint:
            raise ValueError("APK signing identity differs from the pinned release certificate")
        certificates.append(digest)
    if len(certificates) != 2:
        raise ValueError("Expected two signed Release APKs")
    return certificates


def main() -> None:
    if len(sys.argv) != 2 or sys.argv[1] not in ("prepare", "verify"):
        raise ValueError("Usage: release_signing.py prepare|verify")
    if sys.argv[1] == "prepare":
        raw = os.environ.get("ANDROID_RELEASE_SIGNING", "")
        # Mask extracted values too: GitHub initially knows only the complete JSON secret.
        config = parse_configuration(raw)
        for password in {config["store_password"], config["key_password"]}:
            print("::add-mask::" + password)
        prepare_environment(raw, Path(os.environ["RUNNER_TEMP"]), Path(os.environ["GITHUB_ENV"]))
        print("Release signing configuration prepared")
    else:
        tool = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0/apksigner"
        verified = verify_apks(ROOT, str(tool))
        print("Verified both Release APKs with certificate SHA-256: " + verified[0])


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        sys.exit(str(error))
