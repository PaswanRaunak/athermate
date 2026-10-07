#!/usr/bin/env python3
"""Prepare APK + update metadata locally. Never commits, uploads, or publishes."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
# Public certificate fingerprint of this fork's signing key (the machine debug
# keystore that built every distributed AtherMate build), not a private key.
PUBLISHER_CERT = '5e1f3e5c5bafcd7069f61dc06e3a087f49f455132f74959d95d16212ad651a37'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', type=Path)
parser.add_argument('--output', type=Path, default=ROOT / 'android/app/build/release-upload')
args = parser.parse_args()
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'Android/Sdk')
build_tools = sdk / 'build-tools/34.0.0'
# Windows ships apksigner as a batch wrapper and adds .exe extensions to tools.
apksigner = build_tools / ('apksigner.bat' if os.name == 'nt' else 'apksigner')
aapt = build_tools / ('aapt.exe' if os.name == 'nt' else 'aapt')

def run(command):
    return subprocess.check_output([str(part) for part in command], text=True)

if not args.apk.is_file():
    parser.error('Build the signed release APK first.')
certificate = run([apksigner, 'verify', '--print-certs', args.apk])
signers = re.findall(r'certificate SHA-256 digest: (\w+)', certificate)
if signers != [PUBLISHER_CERT]:
    parser.error('APK signing key differs from the published app. Use the original keystore.')
info = run([aapt, 'dump', 'badging', args.apk])
package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", info)
if package is None or package[1] != 'io.ather.pro':
    parser.error('Unexpected APK application ID.')
version = package[3]
if not re.fullmatch(r'\d+\.\d+\.\d+', version):
    parser.error('Release version must be major.minor.patch without a local suffix.')
if "application-debuggable" in info:
    parser.error('A public update must use the release build, not a debug APK.')

spec = importlib.util.spec_from_file_location('secret_guard', ROOT / 'scripts/secret-guard.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
private_values = guard.known_secrets()
with zipfile.ZipFile(args.apk) as archive:
    for entry in archive.namelist():
        content = archive.read(entry)
        if any(value in content for value in private_values):
            parser.error('Private account data found in APK. Release blocked.')

args.output.mkdir(parents=True, exist_ok=True)
name = f'Athr+-v{version}-release.apk'
target = args.output / name
if args.apk.resolve() != target.resolve():
    shutil.copy2(args.apk, target)
digest = hashlib.sha256(target.read_bytes()).hexdigest()
metadata = dict(applicationId=package[1], versionName=version, versionCode=int(package[2]), apk=name, sha256=digest)
(args.output / 'update.json').write_text(json.dumps(metadata, indent=2) + '\n')
(args.output / (name + '.sha256')).write_text(f'{digest}  {name}\n')
print(f'Ready: v{version} (code {package[2]}) in {args.output}')
print('Upload these three files to a stable GitHub Release; nothing has been published.')
