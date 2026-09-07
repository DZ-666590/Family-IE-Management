"""Deployment safety tests: real archives/files; only service operations are faked."""
import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "ci_deploy.py"
spec = importlib.util.spec_from_file_location("ci_deploy", SCRIPT)
deploy = importlib.util.module_from_spec(spec) if SCRIPT.exists() else None
if deploy:
    spec.loader.exec_module(deploy)

SHA = "a" * 40


def artifact(commit=SHA):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as jar:
        jar.writestr("META-INF/MANIFEST.MF", "Main-Class: org.springframework.boot.loader.launch.JarLauncher\n")
        jar.writestr("BOOT-INF/classes/static/index.html", "<html>new version</html>")
        jar.writestr("BOOT-INF/classes/static/deployment.json", json.dumps({"commit": commit}))
    raw = buffer.getvalue()
    return raw, gzip.compress(raw), hashlib.sha256(raw).hexdigest()


class DeployTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(deploy, "Deployment helper must exist")
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.jar = self.root / "app.jar"
        self.jar.write_bytes(b"previous jar")
        self.runner = deploy.Deployer(self.jar, self.root / "state", "example.service", "http://localhost")

    def run_deploy(self, checksum=None, commit=SHA, run_id=100):
        raw, compressed, digest = artifact(commit)
        self.runner.deploy(SHA, checksum or digest, run_id, io.BytesIO(compressed))
        return raw

    def test_success_installs_exact_bytes_and_keeps_backup(self):
        with patch.object(self.runner, "restart"), patch.object(self.runner, "wait_ready"):
            raw = self.run_deploy()
        self.assertEqual(self.jar.read_bytes(), raw)
        self.assertEqual(next((self.root / "state/backups").glob("*.jar")).read_bytes(), b"previous jar")
        self.assertEqual(json.loads((self.root / "state/current.json").read_text())["commit"], SHA)

    def test_bad_checksum_leaves_current_jar_untouched(self):
        with self.assertRaisesRegex(ValueError, "checksum"):
            self.run_deploy(checksum="0" * 64)
        self.assertEqual(self.jar.read_bytes(), b"previous jar")

    def test_wrong_commit_in_jar_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "commit"):
            self.run_deploy(commit="b" * 40)
        self.assertEqual(self.jar.read_bytes(), b"previous jar")

    def test_unhealthy_new_version_restores_old_jar_and_metadata(self):
        with patch.object(self.runner, "restart"), patch.object(self.runner, "wait_ready", side_effect=[RuntimeError("not ready"), None]):
            with self.assertRaisesRegex(RuntimeError, "rolled back"):
                self.run_deploy()
        self.assertEqual(self.jar.read_bytes(), b"previous jar")
        self.assertFalse((self.root / "state/current.json").exists())

    def test_restart_failure_also_restores_old_jar(self):
        with patch.object(self.runner, "restart", side_effect=[RuntimeError("restart failed"), None]), patch.object(self.runner, "wait_ready"):
            with self.assertRaisesRegex(RuntimeError, "rolled back"):
                self.run_deploy()
        self.assertEqual(self.jar.read_bytes(), b"previous jar")

    def test_older_run_cannot_overwrite_newer_success(self):
        with patch.object(self.runner, "restart"), patch.object(self.runner, "wait_ready"):
            raw = self.run_deploy(run_id=101)
            with self.assertRaisesRegex(ValueError, "older"):
                self.run_deploy(run_id=100)
        self.assertEqual(self.jar.read_bytes(), raw)

    def test_command_injection_and_invalid_commands_are_rejected(self):
        for command in ["bash", "status; id", f"deploy {SHA} {'0' * 64} 1;id", "deploy x y 1"]:
            with self.subTest(command=command), self.assertRaises(ValueError):
                deploy.parse_command(command)

    def test_valid_command_is_parsed_without_shell(self):
        self.assertEqual(deploy.parse_command(f"deploy {SHA} {'0' * 64} 12"), (SHA, "0" * 64, 12))


if __name__ == "__main__":
    unittest.main()
