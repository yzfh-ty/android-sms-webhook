"""Validate release identity and collect two unsigned, ABI-specific Release APKs."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RELEASE_ABIS = ("arm64-v8a", "x86_64")


def release_plan(source: str, env: dict) -> dict:
    match = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', source, re.MULTILINE)
    if not match:
        raise ValueError("Cannot read versionName from app/build.gradle.kts")
    version = match.group(1)
    expected = f"v{version}"
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", expected):
        raise ValueError("versionName must be X.Y.Z or X.Y.Z-suffix")
    publish = str(env.get("PUBLISH_RELEASE", "false")).lower() == "true"
    tag = env.get("GITHUB_REF_NAME", "") if env.get("GITHUB_REF_TYPE") == "tag" else env.get("RELEASE_TAG", "").strip()
    tag = tag or expected
    if tag != expected:
        raise ValueError(f"Tag must match the app version: expected {expected}")
    return {"version": version, "tag": tag, "publish": str(publish).lower(), "prerelease": str("-" in version).lower()}


def package_apks(root: Path, plan: dict) -> list:
    directory = root / "app/build/outputs/apk/release"
    metadata = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
    if metadata["applicationId"] != "com.tyverse.smswebhook" or metadata["variantName"] != "release":
        raise ValueError("Expected Release APKs with the normal application ID")
    elements = metadata["elements"]
    if len(elements) != len(RELEASE_ABIS):
        raise ValueError("Expected exactly two APKs: arm64-v8a and x86_64; build with -PsplitApks=true")
    outputs = {}
    for element in elements:
        if element["versionName"] != plan["version"]:
            raise ValueError("APK version does not match the release tag")
        filters = element.get("filters", [])
        if len(filters) != 1 or filters[0].get("filterType") != "ABI":
            raise ValueError("Expected one ABI filter per APK; universal or other splits are not allowed")
        abi = filters[0].get("value")
        if abi not in RELEASE_ABIS or abi in outputs:
            raise ValueError("Expected one APK for each of arm64-v8a and x86_64")
        output = Path(element["outputFile"])
        if str(output) != f"app-{abi}-release-unsigned.apk":
            raise ValueError("Unexpected output filename; expected an unsigned ABI-specific Release APK")
        with zipfile.ZipFile(directory / output) as apk:
            native_abis = {name.split("/")[1] for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")}
        if native_abis and native_abis != {abi}:
            raise ValueError(f"APK native libraries do not match {abi}")
        outputs[abi] = directory / output
    dist = root / "dist"
    dist.mkdir(exist_ok=True)
    if any(dist.iterdir()):
        raise ValueError("dist must be empty; refusing to include artifacts from an earlier build")
    artifacts = []
    for abi in RELEASE_ABIS:
        destination = dist / f"android-sms-webhook-{plan['tag']}-{abi}-unsigned.apk"
        shutil.copyfile(outputs[abi], destination)
        artifacts.append(destination)
    (dist / "SHA256SUMS.txt").write_text("".join(
        f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n" for path in artifacts), encoding="utf-8")
    return artifacts


def main() -> None:
    plan = release_plan((ROOT / "app/build.gradle.kts").read_text(encoding="utf-8"), os.environ)
    if "--package" in sys.argv:
        for artifact in package_apks(ROOT, plan):
            print(artifact.name)
        return
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    existing = subprocess.run(["git", "rev-parse", "--verify", f"refs/tags/{plan['tag']}^{{commit}}"],
                              cwd=ROOT, text=True, capture_output=True)
    if plan["publish"] == "true" and existing.returncode == 0 and existing.stdout.strip() != commit:
        raise ValueError("The release tag already points to another commit; use the matching ref or a new version")
    plan["commit"] = commit
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write("".join(f"{key}={value}\n" for key, value in plan.items()))
    print(json.dumps(plan))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
