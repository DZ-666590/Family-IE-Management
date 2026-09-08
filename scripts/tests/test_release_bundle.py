"""Real bundle/files regression coverage; no application or upstream starts."""
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from test_ci_deploy import deploy, artifact, SHA

FILES = ('server.py', 'overseas.py', 'overseas_sources.py', 'requirements.txt')


def bundle(missing=None, corrupt=None, extra=None, commit=SHA):
    jar, _, _ = artifact(commit)
    contents = {'app.jar': jar, **{f'market/{n}': b'# fixture\n' for n in FILES}}
    manifest = {'schema': 1, 'commit': commit, 'files': {n: hashlib.sha256(b).hexdigest() for n, b in contents.items()}}
    if missing:
        del contents[missing]
    if corrupt:
        contents[corrupt] += b'changed'
    if extra:
        contents[extra] = b'bad'
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w') as archive:
        archive.writestr('release.json', json.dumps(manifest))
        for name, raw in contents.items():
            archive.writestr(name, raw)
    raw = out.getvalue()
    return gzip.compress(raw), hashlib.sha256(raw).hexdigest()


class BundleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.jar = self.root / 'app.jar'
        self.jar.write_bytes(b'old jar')
        self.market = self.root / 'market'
        self.market.mkdir()
        old = self.market / 'legacy'
        old.mkdir()
        (old / 'server.py').write_text('# old')
        (self.market / 'current').symlink_to(old)
        self.old = old.resolve()
        req = hashlib.sha256(b'# fixture\n').hexdigest()
        runtime = self.market / 'runtimes' / req
        (runtime / '.venv/bin').mkdir(parents=True)
        (runtime / 'requirements.txt').write_bytes(b'# fixture\n')
        (runtime / '.venv/bin/python').symlink_to('/usr/bin/python3')
        self.runner = deploy.Deployer(self.jar, self.root / 'state', 'app', 'http://localhost')
        self.runner.market_root = self.market
        self.runner.market_service = 'market'
        self.runner.market_url = 'http://127.0.0.1:8091'

    def run_bundle(self, **kwargs):
        data, digest = bundle(**kwargs)
        self.runner.deploy(SHA, digest, 100, io.BytesIO(data))

    def test_pair_installed_and_previous_adapter_recorded(self):
        with patch.object(self.runner, 'restart'), patch.object(self.runner, 'wait_ready'), patch.object(deploy.subprocess, 'run'):
            self.run_bundle()
        self.assertEqual(deploy.jar_commit(self.jar), SHA)
        self.assertEqual((self.market / 'current/overseas.py').read_bytes(), b'# fixture\n')
        state = json.loads((self.root / 'state/current.json').read_text())
        self.assertEqual(state['previous_adapter'], str(self.old))

    def test_missing_overseas_fails_before_service_mutation(self):
        with patch.object(deploy.subprocess, 'run') as process:
            with self.assertRaises(ValueError):
                self.run_bundle(missing='market/overseas.py')
        process.assert_not_called()
        self.assertEqual(self.jar.read_bytes(), b'old jar')

    def test_corruption_and_traversal_are_rejected(self):
        for args in ({'corrupt': 'market/overseas.py'}, {'extra': '../escape'}, {'commit': 'b' * 40}):
            with self.subTest(args=args), self.assertRaises(ValueError):
                self.run_bundle(**args)
        self.assertEqual((self.market / 'current').resolve(), self.old)

    def test_failed_health_restores_both_components(self):
        with patch.object(self.runner, 'restart'), patch.object(self.runner, 'wait_ready', side_effect=[RuntimeError('HK failed'), None]), patch.object(deploy.subprocess, 'run'):
            with self.assertRaisesRegex(RuntimeError, 'rolled back'):
                self.run_bundle()
        self.assertEqual(self.jar.read_bytes(), b'old jar')
        self.assertEqual((self.market / 'current').resolve(), self.old)
        self.assertFalse((self.root / 'state/current.json').exists())

    def test_real_overseas_response_contract_is_accepted(self):
        def response(url, **kwargs):
            if url.endswith('/health'):
                value = {'commit': SHA, 'status': 'ready'}
            else:
                market = 'HK' if 'market=HK' in url else 'US'
                symbol = '00700' if market == 'HK' else 'AAPL'
                instrument = {'market': market, 'symbol': symbol}
                value = ({'items': [instrument], 'state': 'READY', 'stale': False}
                         if '/search?' in url else
                         {'instrument': instrument, 'symbol': symbol, 'source': 'SINA',
                          'adjustment': 'none', 'supported': True, 'stale': False,
                          'bars': [{'close': 1}]})
            return io.BytesIO(json.dumps(value).encode())
        with patch.object(deploy.urllib.request, 'urlopen', side_effect=response):
            self.runner.check_market(SHA)

    def test_legacy_jar_only_artifact_rejected(self):
        _, data, digest = artifact()
        with self.assertRaises(ValueError):
            self.runner.deploy(SHA, digest, 100, io.BytesIO(data))
        self.assertEqual(self.jar.read_bytes(), b'old jar')

    def test_missing_runtime_and_pending_journal_fail_closed(self):
        (self.market / 'runtimes').rename(self.market / 'unavailable')
        with self.assertRaisesRegex(ValueError, 'runtime'):
            self.run_bundle()
        (self.root / 'state/pending.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'operator'):
            self.run_bundle()

    def test_recovery_failure_keeps_journal_and_blocks_following_deploy(self):
        with patch.object(self.runner, 'restart'), patch.object(self.runner, 'wait_ready', side_effect=RuntimeError('upstream down')), patch.object(deploy.subprocess, 'run'):
            with self.assertRaisesRegex(RuntimeError, 'operator required'):
                self.run_bundle()
        self.assertTrue((self.root / 'state/pending.json').is_file())
        self.assertEqual(self.jar.read_bytes(), b'old jar')
        self.assertEqual((self.market / 'current').resolve(), self.old)

    def test_partial_switch_failure_restores_adapter_and_jar(self):
        original = deploy.os.replace
        def replace(src, dest):
            if Path(src).name == 'app.jar':
                raise OSError('injected disk failure')
            return original(src, dest)
        with patch.object(deploy.os, 'replace', side_effect=replace), patch.object(self.runner, 'restart'), patch.object(self.runner, 'wait_ready'), patch.object(deploy.subprocess, 'run'):
            with self.assertRaisesRegex(RuntimeError, 'rolled back'):
                self.run_bundle()
        self.assertEqual((self.market / 'current').resolve(), self.old)
        self.assertEqual(self.jar.read_bytes(), b'old jar')

    def test_lock_rejects_concurrent_receiver(self):
        import fcntl
        self.runner.state.mkdir()
        with (self.runner.state / 'deploy.lock').open('a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError):
                self.run_bundle()

    def test_outer_size_limit_and_truncated_upload_leave_current_intact(self):
        data, digest = bundle()
        with patch.object(deploy, 'MAX_JAR_BYTES', 32), self.assertRaisesRegex(ValueError, 'size limit'):
            self.runner.deploy(SHA, digest, 100, io.BytesIO(data))
        with self.assertRaises(EOFError):
            self.runner.deploy(SHA, digest, 100, io.BytesIO(data[:-8]))
        self.assertEqual(self.jar.read_bytes(), b'old jar')

    def test_builder_output_round_trips_through_receiver(self):
        import importlib.util
        script = Path(__file__).resolve().parents[1] / 'build_release.py'
        spec = importlib.util.spec_from_file_location('build_release', script)
        builder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(builder)
        jar = self.root / 'build.jar'
        jar.write_bytes(artifact()[0])
        source = self.root / 'source'
        source.mkdir()
        for name in FILES:
            (source / name).write_bytes(b'# fixture\n')
        output = self.root / 'out'
        builder.build(jar, source, output, SHA)
        with (output / 'release.zip.gz').open('rb') as stream, patch.object(self.runner, 'restart'), patch.object(self.runner, 'wait_ready'), patch.object(deploy.subprocess, 'run'):
            self.runner.deploy(SHA, (output / 'release.zip.sha256').read_text().split()[0], 100, stream)
        self.assertEqual(deploy.jar_commit(self.jar), SHA)
        (source / 'overseas.py').unlink()
        with self.assertRaises(ValueError):
            builder.build(jar, source, output, SHA)

    def test_duplicate_symlink_and_oversized_members_are_rejected(self):
        import warnings
        data, _ = bundle()
        for case in ('duplicate', 'symlink', 'large'):
            with self.subTest(case=case):
                out = io.BytesIO()
                with zipfile.ZipFile(io.BytesIO(gzip.decompress(data))) as source, zipfile.ZipFile(out, 'w') as archive:
                    for info in source.infolist():
                        raw = source.read(info.filename)
                        if info.filename == 'market/overseas.py':
                            if case == 'symlink':
                                info.external_attr = 0o120777 << 16
                            if case == 'large':
                                raw = b'x' * (1024 * 1024 + 1)
                        archive.writestr(info, raw)
                    if case == 'duplicate':
                        with warnings.catch_warnings():
                            warnings.simplefilter('ignore', UserWarning)
                            archive.writestr('market/server.py', b'other')
                raw = out.getvalue()
                with self.assertRaises(ValueError):
                    self.runner.deploy(SHA, hashlib.sha256(raw).hexdigest(), 100, io.BytesIO(gzip.compress(raw)))
        self.assertEqual(self.jar.read_bytes(), b'old jar')

    def test_wrong_adapter_version_and_empty_us_data_fail_readiness(self):
        for case in ('version', 'empty-us', 'stale-us'):
            def response(url, **kwargs):
                if url.endswith('/health'):
                    value = {'commit': 'b' * 40 if case == 'version' else SHA, 'status': 'ready'}
                elif url.endswith('/api/csrf'):
                    value = {'data': {'token': 'test-token'}}
                elif url.endswith('/deployment.json'):
                    value = {'commit': SHA}
                elif url.endswith('/'):
                    return io.BytesIO(b'<html></html>')
                else:
                    market = 'HK' if 'market=HK' in url else 'US'
                    symbol = '00700' if market == 'HK' else 'AAPL'
                    instrument = {'market': market, 'symbol': symbol}
                    value = ({'items': [instrument], 'state': 'READY', 'stale': False}
                             if '/search?' in url else
                             {'instrument': instrument, 'symbol': symbol, 'source': 'SINA',
                              'adjustment': 'none', 'stale': market == 'US' and case == 'stale-us',
                              'bars': [] if market == 'US' and case == 'empty-us' else [{'close': 1}]})
                return io.BytesIO(json.dumps(value).encode())
            with self.subTest(case=case), patch.object(deploy.urllib.request, 'urlopen', side_effect=response), patch.object(deploy.time, 'monotonic', side_effect=[0, 0, 151]), patch.object(deploy.time, 'sleep'):
                with self.assertRaisesRegex(RuntimeError, 'timed out'):
                    self.runner.wait_ready(SHA)

    def test_term_like_interruption_after_switch_recovers_pair(self):
        with patch.object(self.runner, 'restart', side_effect=[TimeoutError('TERM'), None]), patch.object(self.runner, 'wait_ready'), patch.object(deploy.subprocess, 'run'):
            with self.assertRaisesRegex(RuntimeError, 'rolled back'):
                self.run_bundle()
        self.assertEqual((self.market / 'current').resolve(), self.old)
        self.assertEqual(self.jar.read_bytes(), b'old jar')

    def test_service_stop_failure_cannot_publish_success(self):
        with patch.object(self.runner, 'stop', side_effect=RuntimeError('cannot stop')):
            with self.assertRaisesRegex(RuntimeError, 'operator'):
                self.run_bundle()
        self.assertEqual((self.market / 'current').resolve(), self.old)
        self.assertEqual(self.jar.read_bytes(), b'old jar')
        self.assertTrue((self.root / 'state/pending.json').exists())
