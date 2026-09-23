#!/usr/bin/env python3
"""Validate the Maven runtime JAR and optionally stage a release bundle."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import zipfile


def validate(source, version):
    source = Path(source).resolve()
    target = Path.cwd().resolve() / "target"
    if not source.is_relative_to(target) or not source.is_file():
        raise ValueError("Expected the Maven runtime JAR inside target/")
    if not re.fullmatch(r"[a-z][a-z0-9]*-" + re.escape(version) + r"\.jar", source.name):
        raise ValueError("Runtime JAR filename must use a lowercase alphanumeric name followed by the Maven version")
    with zipfile.ZipFile(source) as jar:
        descriptors = {"plugin.yml", "paper-plugin.yml"}.intersection(jar.namelist())
        if not descriptors:
            raise ValueError("Runtime JAR has no plugin descriptor")
        if jar.testzip() is not None:
            raise ValueError("Runtime JAR is corrupt")
        for name in descriptors:
            text = jar.read(name).decode("utf-8-sig")
            versions = re.findall(
                r'''^version:[ \t]*(?:"([^"\r\n]*)"|'([^'\r\n]*)'|([^\s#]+))(?:(?:[ \t]+\#[^\r\n]*)|[ \t]*)\r?$''',
                text, re.MULTILINE,
            )
            if len(versions) != 1 or next((v for v in versions[0] if v), "") != version:
                raise ValueError(f"{name} version must match Maven version {version}")
    return source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--stage", type=Path)
    parser.add_argument("--dependencies", type=Path)
    args = parser.parse_args()
    try:
        source = validate(args.jar, args.version)
        if args.stage:
            if os.environ["TAG"] != f"v{args.version}":
                raise ValueError("Release tag must match the Maven version")
            dependencies = json.loads(args.dependencies.read_text()) if args.dependencies else []
            digest = hashlib.sha256(source.read_bytes()).hexdigest()
            metadata = {
                "repository": os.environ["GITHUB_REPOSITORY"],
                "commit": os.environ["GITHUB_SHA"],
                "tag": os.environ["TAG"],
                "run": f"{os.environ['GITHUB_SERVER_URL']}/{os.environ['GITHUB_REPOSITORY']}/actions/runs/{os.environ['GITHUB_RUN_ID']}",
                "plugin_dependencies": dependencies,
                "artifact": source.name,
                "sha256": digest,
            }
            args.stage.mkdir()
            shutil.copyfile(source, args.stage / source.name)
            (args.stage / "SHA256SUMS").write_text(f"{digest}  {source.name}\n")
            (args.stage / "build.json").write_text(json.dumps(metadata, indent=2) + "\n")
        print(f"Verified {source.name}: filename and embedded version match {args.version}")
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Plugin artifact validation failed: {error}\n")


if __name__ == "__main__":
    main()
