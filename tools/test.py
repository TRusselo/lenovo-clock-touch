#!/usr/bin/env python3
"""Validate manifest, compile SDK + plugin + tests, and run every *Test class."""
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_sdk import android_platform

root = Path(__file__).resolve().parents[1]
java_home = os.environ.get('JAVA_HOME')


def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name


sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '/opt/android-sdk')))
if not sdk_root.is_dir():
    sdk_root = Path.home() / 'android-sdk'
platform = str(android_platform(sdk_root))


def check_manifest(manifest):
    assert manifest['schemaVersion'] == 1
    assert manifest['apiVersion'] == 1
    assert re.fullmatch(r'[a-z][a-z0-9]*(?:-[a-z0-9]+)*', manifest['id'])
    assert manifest['minAndroidSdk'] >= 24
    bad_num = [s['key'] for s in manifest['settings'] if s['type'] == 'number' and not all(k in s for k in ('min', 'max', 'step'))]
    assert not bad_num, f'number settings missing min/max/step: {bad_num}'
    trigger_ids = [t['id'] for t in manifest.get('triggers', [])]
    assert trigger_ids == ['singleTap', 'doubleTap', 'tripleTap'], f'unexpected triggers: {trigger_ids}'
    for tid in trigger_ids:
        assert re.fullmatch(r'[a-z][a-zA-Z0-9]*', tid)
    setting_groups = {s.get('group', 'Settings') for s in manifest['settings']} | {
        b.get('group', 'Settings') for b in manifest.get('buttons', [])
    }
    for g in manifest.get('groups', []):
        assert g['title'] in setting_groups, f"group {g['title']} not in settings/buttons"
        for r in g.get('readings', []):
            assert re.fullmatch(r'\*|(sensor|text_sensor|binary_sensor|select|switch)\.([a-z][a-z0-9_]{0,39}|\*)', r), f'bad reading ref: {r}'


manifest = json.loads((root / 'kiosk-satellite-plugin.json').read_text())
check_manifest(manifest)
print('PASS manifest')

sources = [
    *sorted((root / 'sdk/src').rglob('*.java')),
    *sorted((root / 'src').rglob('*.java')),
    *sorted((root / 'tests').rglob('*.java')),
]
tests = sorted(p for p in (root / 'tests').rglob('*Test.java'))
only = sys.argv[1:]
failed = 0
with tempfile.TemporaryDirectory(prefix='lenovotouch-test-') as out:
    subprocess.run(
        [tool('javac'), '--release', '8', '-Xlint:-options', '-cp', platform, '-d', out, *map(str, sources)],
        check=True,
    )
    for t in tests:
        name = '.'.join(t.relative_to(root / 'tests').with_suffix('').parts)
        if only and t.stem not in only:
            continue
        r = subprocess.run([tool('java'), '-ea', '-cp', os.pathsep.join([out, platform]), name])
        print(('PASS ' if r.returncode == 0 else 'FAIL ') + name, flush=True)
        failed += r.returncode != 0
print(f'{len(tests) if not only else len(only)} test classes, {failed} failed')
sys.exit(1 if failed else 0)
