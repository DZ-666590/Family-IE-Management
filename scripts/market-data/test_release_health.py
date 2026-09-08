"""Health/version behavior without starting an HTTP or business service."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from server import Handler, release_health


class ReleaseHealthTest(unittest.TestCase):
    def test_health_requires_all_exact_release_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = {name: b'# fixture' for name in
                     ('server.py', 'overseas.py', 'overseas_sources.py', 'requirements.txt')}
            for name, raw in files.items():
                (root / name).write_bytes(raw)
            marker = {'schema': 1, 'commit': 'a' * 40,
                      'files': {name: hashlib.sha256(raw).hexdigest() for name, raw in files.items()}}
            (root / 'deployment.json').write_text(json.dumps(marker))
            self.assertEqual(release_health(root)['commit'], 'a' * 40)
            (root / 'overseas.py').write_text('corrupt')
            self.assertEqual(release_health(root)['status'], 'unversioned')
            (root / 'overseas.py').unlink()
            self.assertEqual(release_health(root)['status'], 'unversioned')

    def test_route_returns_startup_snapshot_and_fails_unversioned(self):
        handler = object.__new__(Handler)
        handler.path = '/health'
        responses = []
        handler._json = lambda code, data: responses.append((code, data))
        handler.release = {'status': 'ready', 'commit': 'a' * 40}
        handler.do_GET()
        self.assertEqual(responses[-1], (200, handler.release))
        handler.release = None
        handler.do_GET()
        self.assertEqual(responses[-1][0], 503)
