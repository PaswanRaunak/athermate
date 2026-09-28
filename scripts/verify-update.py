#!/usr/bin/env python3
"""Verify the update identity. Optional --install uses adb install -r, never uninstall/clear."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', nargs='?', type=Path, default=ROOT / 'ScootScribe-v1.1.0-update.apk')
parser.add_argument('--original', type=Path, default=ROOT / 'Ather Pro - v1.0.0.apk')
parser.add_argument('--install', action='store_true', help='Install on a connected phone, preserving app data')
parser.add_argument('--serial', help='ADB device serial, needed when multiple devices are connected')
args = parser.parse_args()
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'Android/Sdk')
versions = sorted(sdk.glob('build-tools/*'), key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.name)), reverse=True)
if not versions:
    parser.error('Set ANDROID_HOME to an Android SDK with build-tools.')
build_tools = versions[0]

def run(command):
    return subprocess.check_output([str(part) for part in command], text=True).strip()

def identity(apk):
    if not apk.is_file():
        parser.error(f'APK missing: {apk}')
    certificate = run([build_tools / 'apksigner', 'verify', '--print-certs', apk])
    fingerprints = re.findall(r'certificate SHA-256 digest: (\w+)', certificate)
    if len(fingerprints) != 1:
        parser.error('Expected one verified signing certificate.')
    info = run([build_tools / 'aapt', 'dump', 'badging', apk])
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", info)
    if package is None:
        parser.error('Could not read the APK package/version.')
    return package[1], int(package[2]), package[3], fingerprints[0]

original = identity(args.original)
update = identity(args.apk)
if update[0] != original[0] or update[0] != 'io.ather.pro':
    parser.error('Application ID mismatch; this would not replace the existing app.')
if update[3] != original[3]:
    parser.error('Signing certificate mismatch; data-preserving update is not possible with this APK.')
if update[1] <= original[1]:
    parser.error('The update needs a higher version code than the supplied original.')
print(f'PASS: package {update[0]}, version {original[2]} ({original[1]}) -> {update[2]} ({update[1]})')
print(f'PASS: matching signing certificate SHA-256 {update[3]}')
print(f'APK SHA-256: {hashlib.sha256(args.apk.read_bytes()).hexdigest()}')

if args.install:
    adb = sdk / 'platform-tools/adb'
    devices = [line.split()[0] for line in run([adb, 'devices']).splitlines()[1:] if len(line.split()) >= 2 and line.split()[1] == 'device']
    serial = args.serial or (devices[0] if len(devices) == 1 else None)
    if serial not in devices:
        parser.error('Connect and authorize the phone. Select --serial if multiple devices are connected.')
    device = [adb, '-s', serial]
    installed = run(device + ['shell', 'pm', 'path', update[0]])
    paths = [line.removeprefix('package:') for line in installed.splitlines() if line.startswith('package:')]
    if not paths:
        parser.error('The original app is not installed on this phone. No update was installed.')
    base = next((path for path in paths if path.endswith('/base.apk')), paths[0])
    with tempfile.TemporaryDirectory(prefix='scootscribe-update-') as tmp:
        pulled = Path(tmp) / 'installed.apk'
        run(device + ['pull', base, pulled])
        current = identity(pulled)
        if current[0] != update[0] or current[3] != update[3] or current[1] > update[1]:
            parser.error('Installed app identity/version is incompatible. No changes were made.')
    subprocess.run(device + ['install', '-r', str(args.apk)], check=True)
    print('Update installed; app data retained. Open ScootScribe to resume monitoring.')
