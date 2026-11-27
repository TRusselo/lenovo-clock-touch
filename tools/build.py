#!/usr/bin/env python3
"""Build the Lenovo Clock Touch Kiosk Satellite plugin ZIP with native BMA253 JNI libs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

from android_sdk import android_platform
from plugin_manifest import build_manifest
from plugin_assets import asset_files, MAX_PACKAGE_BYTES

ROOT = Path(__file__).resolve().parents[1]
NATIVE_TARGETS = (
    ('armeabi-v7a', 'armv7a-linux-androideabi26-clang'),
    ('arm64-v8a', 'aarch64-linux-android26-clang'),
    ('x86_64', 'x86_64-linux-android26-clang'),
)
NATIVE_ENTRY_RE = re.compile(r'native/(arm64-v8a|armeabi-v7a|x86_64)/lib[a-zA-Z0-9_]+\.so')


def find_ndk_bin(sdk_root: Path) -> Path:
    env_ndk = os.environ.get('ANDROID_NDK_HOME') or os.environ.get('ANDROID_NDK_ROOT')
    candidates = []
    if env_ndk:
        candidates.append(Path(env_ndk))
    ndk_dir = sdk_root / 'ndk'
    if ndk_dir.is_dir():
        candidates.extend(sorted(ndk_dir.iterdir(), reverse=True))
    ndk_bundle = sdk_root / 'ndk-bundle'
    if ndk_bundle.is_dir():
        candidates.append(ndk_bundle)
    for base in candidates:
        for host in ('linux-x86_64', 'darwin-x86_64', 'darwin-arm64', 'windows-x86_64'):
            tc = base / 'toolchains/llvm/prebuilt' / host / 'bin'
            if tc.is_dir():
                return tc
    raise SystemExit('Install an Android NDK under $ANDROID_HOME/ndk or set ANDROID_NDK_HOME.')


def build_native_libs(plugin: Path, sdk_root: Path, out_dir: Path):
    src = plugin / 'native/bma253_jni.c'
    if not src.is_file():
        return []
    ndk_bin = find_ndk_bin(sdk_root)
    built = []
    for abi, compiler in NATIVE_TARGETS:
        cc = ndk_bin / compiler
        if not cc.is_file():
            cc = ndk_bin / (compiler + '.cmd')
        if not cc.is_file():
            raise SystemExit(f'Missing NDK compiler {compiler} in {ndk_bin}')
        abi_dir = out_dir / abi
        abi_dir.mkdir(parents=True, exist_ok=True)
        so = abi_dir / 'libbma253.so'
        subprocess.run([
            str(cc),
            '-O2',
            '-Wall',
            '-Wextra',
            '-shared',
            '-fPIC',
            '-Wl,--build-id=none',
            '-Wl,-s',
            '-o',
            str(so),
            str(src),
        ], check=True)
        built.append((f'native/{abi}/libbma253.so', so))
    return built


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('plugin', nargs='?', default=str(ROOT), help='Path to the plugin repository')
    parser.add_argument('--version', help='Package version override, normally supplied by the release tag')
    parser.add_argument('--android-platform', help='Installed platform version, for example 35 or 37.0')
    args = parser.parse_args()
    plugin = Path(args.plugin).resolve()
    try:
        manifest_bytes = build_manifest(plugin / 'kiosk-satellite-plugin.json', args.version)
    except ValueError as error:
        parser.error(str(error))
    manifest = json.loads(manifest_bytes)
    try:
        assets = asset_files(plugin)
    except ValueError as error:
        parser.error(str(error))

    sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '/opt/android-sdk')))
    if not sdk_root.is_dir():
        sdk_root = Path.home() / 'android-sdk'
    java_root = os.environ.get('JAVA_HOME')

    def java_tool(name):
        return str(Path(java_root) / 'bin' / name) if java_root else name

    build_tools = sorted(
        [*(sdk_root / 'build-tools').glob('*/d8'), *(sdk_root / 'build-tools').glob('*/d8.bat')],
        key=lambda p: tuple(int(v) for v in p.parent.name.split('.') if v.isdigit()),
    )
    if not build_tools:
        raise SystemExit('Set ANDROID_HOME to an Android SDK with build-tools installed.')
    try:
        platform = android_platform(sdk_root, args.android_platform)
    except ValueError as error:
        raise SystemExit(str(error)) from error

    out = plugin / 'dist'
    out.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='kiosk-plugin-') as temp_str:
        temp = Path(temp_str)
        sdk_classes, classes, dex, native_out = [temp / name for name in ('sdk', 'classes', 'dex', 'native')]
        for folder in (sdk_classes, classes, dex, native_out):
            folder.mkdir()

        subprocess.run(
            [java_tool('javac'), '--release', '8', '-Xlint:-options', '-cp', str(platform), '-d', str(sdk_classes),
             *map(str, sorted((ROOT / 'sdk/src').rglob('*.java')))],
            check=True,
        )
        sdk_jar = out / 'kiosk-plugin-sdk-1.jar'
        subprocess.run([java_tool('jar'), 'cf', str(sdk_jar), '-C', str(sdk_classes), '.'], check=True)
        subprocess.run(
            [java_tool('javac'), '--release', '8', '-Xlint:-options', '-cp',
             os.pathsep.join([str(sdk_jar), str(platform)]), '-d', str(classes),
             *map(str, sorted((plugin / 'src').rglob('*.java')))],
            check=True,
        )
        subprocess.run(
            [str(build_tools[-1]), '--min-api', str(manifest['minAndroidSdk']), '--lib', str(platform),
             '--classpath', str(sdk_jar), '--output', str(dex),
             *map(str, sorted(classes.rglob('*.class')))],
            check=True,
        )

        native_libs = build_native_libs(plugin, sdk_root, native_out)

        jar = temp / 'plugin.jar'

        def add(archive, name, data):
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, data)

        with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(dex.glob('*.dex')):
                add(archive, file.name, file.read_bytes())

        package = out / f"{manifest['id']}-{manifest['version']}.zip"
        license_text = (plugin / 'LICENSE').read_bytes()
        expanded = (
            len(manifest_bytes)
            + jar.stat().st_size
            + len(license_text)
            + sum(file.stat().st_size for _, file in assets)
            + sum(file.stat().st_size for _, file in native_libs)
        )
        if expanded > MAX_PACKAGE_BYTES:
            raise SystemExit('Expanded plugin exceeds 4 MB')

        with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED) as archive:
            add(archive, 'kiosk-satellite-plugin.json', manifest_bytes)
            add(archive, 'plugin.jar', jar.read_bytes())
            add(archive, 'LICENSE', license_text)
            for name, file in assets:
                add(archive, name, file.read_bytes())
            for name, file in native_libs:
                add(archive, name, file.read_bytes())

        with zipfile.ZipFile(package) as archive:
            names = archive.namelist()
        allowed = {'kiosk-satellite-plugin.json', 'plugin.jar', 'LICENSE'}
        unexpected = [
            n for n in names
            if n not in allowed and not n.startswith('assets/') and not NATIVE_ENTRY_RE.fullmatch(n)
        ]
        if unexpected or len(names) != len(set(names)):
            package.unlink()
            raise SystemExit(f'Kiosk Satellite would refuse these ZIP entries: {unexpected or names}')
        if package.stat().st_size > MAX_PACKAGE_BYTES:
            package.unlink()
            raise SystemExit('Plugin ZIP must be at most 4 MB')

        digest = hashlib.sha256(package.read_bytes()).hexdigest()
        package.with_suffix('.zip.sha256').write_text(f'{digest}  {package.name}\n')
        (out / 'kiosk-satellite-plugin.json').write_bytes(manifest_bytes)
        print(f'Package: {package}\nSHA-256: {digest}\nNative: {[n for n, _ in native_libs]}\nSDK: {sdk_jar}')


if __name__ == '__main__':
    main()
