#!/usr/bin/env python3
"""Build a fixed-member release bundle from one checkout, using only stdlib."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import zipfile

ADAPTER_FILES = ('server.py', 'overseas.py', 'overseas_sources.py', 'requirements.txt')


def build(jar, adapter, output, commit):
    if not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Full commit required')
    with zipfile.ZipFile(jar) as archive:
        if json.loads(archive.read('BOOT-INF/classes/static/deployment.json'))['commit'] != commit:
            raise ValueError('JAR commit mismatch')
    paths = {'app.jar': Path(jar), **{f'market/{n}': Path(adapter) / n for n in ADAPTER_FILES}}
    for path in paths.values():
        if path.is_symlink() or not path.is_file():
            raise ValueError(f'Regular release file required: {path.name}')
    manifest = {'schema': 1, 'commit': commit, 'files': {
        name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in paths.items()}}
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    bundle = output / 'release.zip'
    with zipfile.ZipFile(bundle, 'w', compression=zipfile.ZIP_STORED) as archive:
        archive.writestr('release.json', json.dumps(manifest, sort_keys=True))
        for name, path in paths.items():
            archive.writestr(name, path.read_bytes())
    raw = bundle.read_bytes()
    (output / 'release.zip.sha256').write_text(hashlib.sha256(raw).hexdigest() + '  release.zip\n')
    with (output / 'release.zip.gz').open('wb') as dest:
        with gzip.GzipFile(filename='', mode='wb', fileobj=dest, mtime=0) as compressed:
            compressed.write(raw)
    bundle.unlink()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', required=True)
    parser.add_argument('--adapter', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--commit', required=True)
    args = parser.parse_args()
    build(args.jar, args.adapter, args.output, args.commit)
