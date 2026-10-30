"""Validate release identity and collect Debug and unsigned Release APKs."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


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
    dist = root / "dist"
    dist.mkdir(exist_ok=True)
    if any(dist.iterdir()):
        raise ValueError("dist must be empty; refusing to include artifacts from an earlier build")
    artifacts = []
    for variant in ["debug", "release"]:
        directory = root / "app/build/outputs/apk" / variant
        metadata = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
        elements = metadata["elements"]
        if metadata["applicationId"] != "com.tyverse.smswebhook" or len(elements) != 1:
            raise ValueError("Expected one APK with the normal application ID")
        element = elements[0]
        if element["versionName"] != plan["version"]:
            raise ValueError("APK version does not match the release tag")
        output = Path(element["outputFile"])
        if output.name != str(output):
            raise ValueError("Unexpected APK output path")
        suffix = "debug" if variant == "debug" else "release-unsigned"
        if output.name != f"app-{suffix}.apk":
            raise ValueError(f"Expected app-{suffix}.apk; refusing to mislabel a different build")
        destination = dist / f"android-sms-webhook-{plan['tag']}-{suffix}.apk"
        shutil.copyfile(directory / output, destination)
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
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
