#!/usr/bin/env python3
"""Stopped-server ownership rollback preview; conflicts never overwrite subsequent progress."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import yaml

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("data", type=Path, help="CompanionPets data directory")
parser.add_argument("plan", help="Migration plan name, such as plan-0001")
parser.add_argument("--apply", action="store_true", help="Apply only an exact, conflict-free rollback")
args = parser.parse_args()
if args.data.joinpath("pets-recovery-required").exists():
    parser.error("Stop the server cleanly first; pets-recovery-required exists")
folder = args.data / "ownership-migrations"
plan = yaml.safe_load((folder / (args.plan + ".yml")).read_text())
source = args.data / "pets.yml"
archive = folder / (args.plan + ".original.yml")
hash_of = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
if hash_of(archive) != plan["before"]:
    parser.error("Immutable migration source backup failed verification")
current = hash_of(source)
if current == plan["before"]:
    print("Already at the archived source; no write required")
    raise SystemExit(0)
if current != plan["after"]:
    parser.error("CONFLICT: pets.yml changed after migration; preserve current pets/items/estates and reconcile manually")
print("No conflicts: would restore " + str(archive) + "; the stopped server also needs the matching old JAR")
if args.apply:
    backup = source.with_name("pets.pre-rollback.yml")
    if backup.exists():
        parser.error("Rollback preservation copy already exists; retain and review it first")
    shutil.copy2(source, backup)
    with backup.open("rb") as handle:
        os.fsync(handle.fileno())
    temp = source.with_name("pets.rollback.new")
    with temp.open("wb") as handle:
        handle.write(archive.read_bytes())
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temp, source)
    descriptor = os.open(source.parent, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    print("Restored archived source; preserved current file at " + str(backup))
