#!/usr/bin/env python3
"""Receipt-only, nonshipping qualification preflight. NEVER launches Minecraft/Gradle.

Machine outcome is always `blocked` until a separately reviewed real backend,
world backup and trusted Forge build exist. This CLI is intentionally not a
Gradle task: Forge configuration can fail before a task could emit a receipt.
"""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import uuid

ORIGINAL_VS = Path("/home/dev/mod_source/better-compat-fixes/build/upstream-interface-evidence/originals-261c94fba9b80622482f2cc7a563a97b120b4e6a430aa6a4f07e835a1f9177a1/valkyrienskies-120-2.4.11.jar")
PINNED_VS_SHA256 = "f99f24de62015451a047f90484cf9d25970cac350105b581c22b2d5247ebfd53"
FORGE_MAPPED = Path("/home/dev/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/1.20.1-47.4.22_mapped_official_1.20.1/forge-1.20.1-47.4.22_mapped_official_1.20.1.jar")
FORGE_EXPECTED_SHA256 = "57930057043b9da110644d454c8efc3a247cb213bb310a1e2a46dbac2743f194"
HEX64 = re.compile(r"[0-9a-f]{64}\Z")


class Blocked(Exception):
    pass


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def args(argv):
    if len(argv) != 6 or set(argv[::2]) != {"--attempt-id", "--candidate-sha256", "--historical-probe-sha256"}:
        raise Blocked("usage: --attempt-id CANONICAL_UUID4 --candidate-sha256 FROZEN_SHA256 "
                      "--historical-probe-sha256 REVIEWED_PROBE_SHA256")
    return dict(zip(argv[::2], argv[1::2]))


def canonical_attempt(raw):
    try:
        value = uuid.UUID(raw)
    except (TypeError, ValueError, AttributeError) as error:
        raise Blocked("invalid_attempt_id") from error
    if value.version != 4 or str(value) != raw:
        raise Blocked("invalid_attempt_id: require canonical lowercase UUID4")
    return raw


def safe_directory(path):
    if path.is_symlink() or not path.is_dir():
        raise Blocked("unsafe_directory: " + str(path))
    return os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)


def ensure_uncreated_attempt(repo, attempt):
    # No attempt or world directory is ever created by this preflight.
    parent = repo / "instances"
    if parent.is_symlink() or parent.exists() and not parent.is_dir():
        raise Blocked("unsafe_attempt_parent")
    child = parent / "qualification"
    if child.is_symlink() or child.exists() and not child.is_dir():
        raise Blocked("unsafe_qualification_parent")
    destination = child / attempt
    if destination.exists() or destination.is_symlink():
        raise Blocked("existing_attempt_or_world: " + str(destination))
    return destination


def tree_digest(repo, roots, fixed=()):
    paths = [repo / item for item in fixed]
    for dirname in roots:
        parent = repo / dirname
        if not parent.is_dir() or parent.is_symlink():
            raise Blocked("missing_or_symlinked_input: " + dirname)
        paths.extend(p for p in parent.rglob("*") if p.is_file() or p.is_symlink())
    digest = hashlib.sha256()
    for path in sorted(paths, key=lambda item: item.relative_to(repo).as_posix()):
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(repo.resolve()):
            raise Blocked("missing_or_unsafe_input: " + str(path))
        for ancestor in path.relative_to(repo).parents:
            if (repo / ancestor).is_symlink():
                raise Blocked("symlinked_input_parent: " + str(path))
        digest.update(path.relative_to(repo).as_posix().encode("utf-8") + b"\0")
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        digest.update(b"\0")
    return digest.hexdigest()


def candidate_digest(repo):
    # Every effective authored input EXCEPT historical probe: its bytes are
    # separately pinned below but NEVER count as production qualification.
    return tree_digest(repo, ["src/main", "src/qualificationSupport", "src/test",
                              "tests", "qualification", "scripts", "gradle"],
                       ["build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                        "gradle.lockfile", "gradlew", "gradlew.bat"])


def historical_probe_digest(repo):
    return tree_digest(repo, ["src/probe"])


def write_receipt(repo, attempt, receipt):
    tmp = repo / ".tmp"
    tmp_fd = safe_directory(tmp)
    try:
        try:
            os.mkdir("qualification-receipts", 0o700, dir_fd=tmp_fd)
        except FileExistsError:
            pass
        directory = tmp / "qualification-receipts"
        receipts_fd = os.open("qualification-receipts", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                              dir_fd=tmp_fd)
        try:
            name = attempt + ".json"
            flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
            fd = os.open(name, flags, 0o600, dir_fd=receipts_fd)
            try:
                receipt["receipt_path"] = str(directory / name)
                data = (json.dumps(receipt, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
                with os.fdopen(fd, "wb", closefd=False) as output:
                    output.write(data)
                    output.flush()
                    os.fsync(output.fileno())
            finally:
                os.close(fd)
            os.fsync(receipts_fd)
            return str(directory / name)
        finally:
            os.close(receipts_fd)
    finally:
        os.close(tmp_fd)


def preflight(repo, argv, original_vs=ORIGINAL_VS, forge_mapped=FORGE_MAPPED):
    receipt = {"schema": 1, "status": "blocked", "attempt_id": None,
               "candidate_sha256": None, "historical_probe_sha256": None,
               "original_vs_sha256": None,
               "forge_expected_sha256": FORGE_EXPECTED_SHA256,
               "forge_observed_sha256": None, "reason": [], "receipt_path": None}
    try:
        options = args(argv)
        attempt = canonical_attempt(options["--attempt-id"])
        receipt["attempt_id"] = attempt
        requested = options["--candidate-sha256"]
        requested_probe = options["--historical-probe-sha256"]
        if not HEX64.fullmatch(requested) or not HEX64.fullmatch(requested_probe):
            raise Blocked("invalid_frozen_candidate_or_probe_sha256")
        if repo.is_symlink() or not repo.is_dir():
            raise Blocked("unsafe_repository_root")
        tmp = repo / ".tmp"
        tmp_fd = safe_directory(tmp)
        try:
            lock_fd = os.open("spaceflight-runtime.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW,
                              0o600, dir_fd=tmp_fd)
            try:
                if not stat.S_ISREG(os.fstat(lock_fd).st_mode):
                    raise Blocked("unsafe_project_lock_inode")
                locked_path = os.stat("spaceflight-runtime.lock", dir_fd=tmp_fd, follow_symlinks=False)
                if locked_path.st_ino != os.fstat(lock_fd).st_ino or locked_path.st_dev != os.fstat(lock_fd).st_dev:
                    raise Blocked("changed_project_lock_inode")
                try:
                    fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError as error:
                    raise Blocked("project_lock_contended") from error
                ensure_uncreated_attempt(repo, attempt)
                if (tmp / "qualification-receipts" / (attempt + ".json")).exists() or (
                    tmp / "qualification-receipts" / (attempt + ".json")).is_symlink():
                    raise Blocked("existing_receipt_or_reused_uuid")
                observed = candidate_digest(repo)
                receipt["candidate_sha256"] = observed
                if requested != observed:
                    receipt["reason"].append("frozen_candidate_mismatch")
                probe_observed = historical_probe_digest(repo)
                receipt["historical_probe_sha256"] = probe_observed
                if requested_probe != probe_observed:
                    receipt["reason"].append("historical_probe_pin_mismatch")
                try:
                    receipt["original_vs_sha256"] = sha256(original_vs)
                    if receipt["original_vs_sha256"] != PINNED_VS_SHA256:
                        receipt["reason"].append("original_vs_mismatch")
                except (OSError, ValueError):
                    receipt["reason"].append("original_vs_missing")
                try:
                    receipt["forge_observed_sha256"] = sha256(forge_mapped)
                    if receipt["forge_observed_sha256"] != FORGE_EXPECTED_SHA256:
                        receipt["reason"].append("forge_verification_mismatch")
                except (OSError, ValueError):
                    receipt["reason"].append("forge_verification_missing")
                receipt["reason"].append("production_dispatch_and_full_world_backup_unavailable")
                receipt["receipt_path"] = write_receipt(repo, attempt, receipt)
            finally:
                os.close(lock_fd)  # flock released; no runtime or nested Gradle launch
        finally:
            os.close(tmp_fd)
    except (Blocked, OSError, ValueError) as error:
        receipt["reason"].append(str(error))
    return receipt


def exit_code(receipt):
    # Both codes report BLOCKED, never a passing or executed-failed scenario.
    return 75 if "project_lock_contended" in receipt["reason"] else 2


def main(argv):
    repo = Path(__file__).resolve().parent.parent
    receipt = preflight(repo, argv)
    print(json.dumps(receipt, sort_keys=True, separators=(",", ":")), flush=True)
    return exit_code(receipt)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
