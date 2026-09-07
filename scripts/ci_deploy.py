#!/usr/bin/python3
"""Restricted SSH deployment receiver. Install root-owned; never execute via a shell."""
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile


MAX_JAR_BYTES = 500 * 1024 * 1024
MARKER = "BOOT-INF/classes/static/deployment.json"


def parse_command(command):
    match = re.fullmatch(r"deploy ([0-9a-f]{40}) ([0-9a-f]{64}) ([1-9][0-9]{0,19})", command)
    if not match:
        raise ValueError("Only deploy <commit> <checksum> <run-id> is allowed")
    return match[1], match[2], int(match[3])


def jar_commit(path):
    with zipfile.ZipFile(path) as archive:
        info = archive.getinfo(MARKER)
        if info.file_size > 1024:
            raise ValueError("Oversized deployment marker")
        return json.loads(archive.read(MARKER))["commit"]


class Deployer:
    def __init__(self, jar, state, service, base_url):
        self.jar = Path(jar)
        self.state = Path(state)
        self.service = service
        self.base_url = base_url.rstrip("/")

    def restart(self):
        subprocess.run(["/usr/bin/systemctl", "restart", self.service], check=True, timeout=90)

    def wait_ready(self, commit):
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(self.base_url + "/api/csrf", timeout=5) as response:
                    payload = json.load(response)
                    if not payload.get("data", {}).get("token"):
                        raise ValueError("CSRF API is not ready")
                with urllib.request.urlopen(self.base_url + "/", timeout=5) as response:
                    if b"<html" not in response.read(200000).lower():
                        raise ValueError("Frontend is not ready")
                if commit:
                    with urllib.request.urlopen(self.base_url + "/deployment.json", timeout=5) as response:
                        if json.load(response).get("commit") != commit:
                            raise ValueError("Wrong served version")
                return
            except (OSError, ValueError):
                time.sleep(3)
        raise RuntimeError("Service readiness check timed out")

    def deploy(self, commit, digest, run_id, stream):
        self.state.mkdir(parents=True, exist_ok=True, mode=0o700)
        with (self.state / "deploy.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            current_path = self.state / "current.json"
            current = json.loads(current_path.read_text()) if current_path.exists() else {}
            if run_id < current.get("run_id", 0):
                raise ValueError("Refusing older workflow run")
            if not self.jar.is_file() or self.jar.is_symlink():
                raise ValueError("Existing regular application JAR is required")
            # Same filesystem as target so os.replace is atomic. Never extract ZIP paths.
            with tempfile.TemporaryDirectory(prefix=".ci-deploy-", dir=self.jar.parent) as directory:
                candidate = Path(directory) / "candidate.jar"
                checksum = hashlib.sha256()
                count = 0
                with gzip.GzipFile(fileobj=stream, mode="rb") as source, candidate.open("wb") as dest:
                    while chunk := source.read(1024 * 1024):
                        count += len(chunk)
                        if count > MAX_JAR_BYTES:
                            raise ValueError("Artifact exceeds size limit")
                        checksum.update(chunk)
                        dest.write(chunk)
                    dest.flush()
                    os.fsync(dest.fileno())
                if checksum.hexdigest() != digest:
                    raise ValueError("Artifact checksum mismatch")
                with zipfile.ZipFile(candidate) as archive:
                    if "Main-Class:" not in archive.read("META-INF/MANIFEST.MF").decode():
                        raise ValueError("Not an executable JAR")
                    archive.getinfo("BOOT-INF/classes/static/index.html")
                if jar_commit(candidate) != commit:
                    raise ValueError("Artifact commit mismatch")
                backups = self.state / "backups"
                backups.mkdir(exist_ok=True, mode=0o700)
                backup = backups / f"{run_id}-{time.time_ns()}.jar"
                shutil.copy2(self.jar, backup)
                try:
                    old_commit = jar_commit(backup)
                except (KeyError, ValueError, zipfile.BadZipFile):
                    old_commit = None  # First migration from a manually deployed JAR.
                candidate.chmod(0o644)
                swapped = False
                try:
                    os.replace(candidate, self.jar)
                    swapped = True
                    self.restart()
                    self.wait_ready(commit)
                    metadata = Path(directory) / "current.json"
                    metadata.write_text(json.dumps({"commit": commit, "sha256": digest,
                                                    "run_id": run_id, "backup": str(backup)}))
                    os.replace(metadata, current_path)
                except BaseException as error:
                    if swapped:
                        rollback = Path(directory) / "rollback.jar"
                        shutil.copy2(backup, rollback)
                        os.replace(rollback, self.jar)
                        try:
                            self.restart()
                            self.wait_ready(old_commit)
                        except BaseException:
                            raise RuntimeError("Previous JAR restored but recovery health check FAILED; operator required") from error
                        raise RuntimeError("Deployment failed; rolled back to previous JAR") from error
                    raise
                print(json.dumps({"result": "deployed", "commit": commit, "sha256": digest}), flush=True)


def main():
    os.umask(0o077)
    # Only this root-owned file controls paths. SSH input cannot override them.
    config = json.loads(Path("/etc/family-finance/ci-deploy.json").read_text())
    commit, digest, run_id = parse_command(os.environ.get("SSH_ORIGINAL_COMMAND", ""))
    runner = Deployer(config["jar"], config["state"], config["service"], config["base_url"])
    # Disconnection must not interrupt the backup/replace/recovery sequence.
    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    def interrupted(signum, frame):
        raise TimeoutError("Deployment interrupted; attempting recovery")
    signal.signal(signal.SIGTERM, interrupted)
    runner.deploy(commit, digest, run_id, sys.stdin.buffer)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Deployment rejected or failed: {error}", file=sys.stderr)
        sys.exit(1)
