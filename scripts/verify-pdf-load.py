#!/usr/bin/env python3
"""Opt-in PDF stress run on a named test emulator, preserving release app/user data."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
from verification import expected_tests, instrumentation_result, require_test_device, stage_private_fixture

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)
p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--fixtures', type=Path, default=ROOT / '.tools/pdf-load-fixtures')
p.add_argument('--test-apk', type=Path, default=ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
args = p.parse_args()
adb = os.environ.get('ADB', str(ROOT / '.tools/android-sdk/platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')))
pkg = 'com.poyal.yeobaek.debug'


def command(*parts, **kw):
    if kw.get('text'): kw.setdefault('encoding', 'utf-8')
    return subprocess.run([adb, '-s', args.serial, *parts], check=True, **kw)


def output(*parts):
    return command(*parts, capture_output=True, text=True).stdout.strip()


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


avd = output('emu', 'avd', 'name').splitlines()[0]
require_test_device(args.serial, avd)
fixtures = json.loads((args.fixtures / 'manifest.json').read_text())
expected = {'100MiB-mixed.pdf', '300MiB-scan.pdf'}
assert {f['file'] for f in fixtures['fixtures']} == expected
for fixture in fixtures['fixtures']:
    file = args.fixtures / fixture['file']
    assert file.stat().st_size == fixture['bytes']
    assert sha256(file) == fixture['sha256']

stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
out = ROOT / '.tools/verification' / f'{stamp}-{args.serial}-pdf-load'
out.mkdir(parents=True)
apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
test = args.test_apk
manifest = dict(serial=args.serial, avd=avd, api=output('shell', 'getprop', 'ro.build.version.sdk'),
                fingerprint=output('shell', 'getprop', 'ro.build.fingerprint'),
                display=output('shell', 'wm', 'size'), density=output('shell', 'wm', 'density'),
                apk_sha256=sha256(apk), test_apk_sha256=sha256(test), fixtures=fixtures,
                thresholds=dict(app_pss_mib=384, renderer_pss_mib=512, combined_pss_mib=768,
                                warm_to_late_growth_mib=96, app_fd_growth=24, viewport_timeout_seconds=30))
(out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
for file in [apk, test]:
    command('install', '-r', str(file), stdout=subprocess.DEVNULL)
output('shell', 'run-as', pkg, 'mkdir', '-p', 'files/pdf-load', 'files/pdf-load-results')
for name in sorted(expected | {'manifest.json'}):
    local = args.fixtures / name
    remote = subprocess.run([adb, '-s', args.serial, 'shell', 'run-as', pkg, 'sha256sum', f'files/pdf-load/{name}'],
                            capture_output=True, text=True, encoding='utf-8')
    if remote.returncode != 0 or remote.stdout.split()[0] != sha256(local):
        stage_private_fixture(adb, args.serial, pkg, local, f'files/pdf-load/{name}')
    actual = int(output('shell', 'run-as', pkg, 'stat', '-c', '%s', f'files/pdf-load/{name}'))
    assert actual == (args.fixtures / name).stat().st_size, f'Transfer failed: {name}'
# Remove only artifacts of this explicit suite; keep library databases/preferences and release app untouched.
output('shell', 'run-as', pkg, 'sh', '-c', shlex.quote('rm -f files/pdf-load-results/*.json'))
print(f'Evidence: {out}', flush=True)
runner = pkg + '.test/androidx.test.runner.AndroidJUnitRunner'
with (out / 'instrumentation.txt').open('w', encoding='utf-8') as log, (out / 'memory.ndjson').open('w', encoding='utf-8') as memory_log:
    process = subprocess.Popen([adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                                'com.poyal.yeobaek.PdfLoadTest', runner], stdout=log, stderr=subprocess.STDOUT)
    samples = []
    start = time.monotonic()
    try:
        while process.poll() is None:
            row = dict(elapsed_s=round(time.monotonic() - start, 3))
            for kind, name in [('app', pkg), ('renderer', pkg + ':pdf')]:
                lookup = [name, name + ':com.poyal.yeobaek.pdf.PdfRenderService'] if kind == 'renderer' else [name]
                pid_result = subprocess.run([adb, '-s', args.serial, 'shell', 'pidof', *lookup], capture_output=True, text=True, encoding='utf-8')
                if not pid_result.stdout.strip():
                    continue
                pid = pid_result.stdout.split()[0]
                raw = output('shell', 'dumpsys', 'meminfo', '-s', pid)
                # A package query can list multiple processes; match the exact process section.
                sections = re.split(r'\*\* MEMINFO in pid (\d+) \[([^\]]+)\] \*\*', raw)
                for i in range(1, len(sections), 3):
                    if sections[i] == pid:
                        found = re.search(r'TOTAL PSS:\s+(\d+)|TOTAL:\s+(\d+)', sections[i + 2])
                        if found:
                            row[kind + '_pss_kib'] = int(found.group(1) or found.group(2))
                            row[kind + '_pid'] = int(sections[i])
            samples.append(row); memory_log.write(json.dumps(row) + '\n'); memory_log.flush()
            if len(samples) % 15 == 0:
                print(json.dumps(row), flush=True)
            if time.monotonic() - start > 1200:
                output('shell', 'am', 'force-stop', pkg)
                raise TimeoutError('PDF suite exceeded 20 minutes')
            time.sleep(1)
    finally:
        process.wait(timeout=30)
raw = (out / 'instrumentation.txt').read_text(encoding='utf-8')
result_files_pass = True
for name in sorted(expected):
    try:
        data = output('exec-out', 'run-as', pkg, 'cat', f'files/pdf-load-results/{name}.json')
        (out / f'{name}.json').write_text(data + '\n')
        records = json.loads(data)
        result_files_pass = result_files_pass and records[-1]['phase'] == 'pass'
    except (subprocess.CalledProcessError, ValueError, KeyError, IndexError):
        result_files_pass = False
instrumentation = instrumentation_result(raw, expected_tests(ROOT, 'app/src/pdfLoadTest', ['com.poyal.yeobaek.PdfLoadTest']), process.returncode)
success = instrumentation['status'] == 'PASS'
peaks = {kind: max((r.get(kind + '_pss_kib', 0) for r in samples), default=0) for kind in ['app', 'renderer']}
combined = max((r.get('app_pss_kib', 0) + r.get('renderer_pss_kib', 0) for r in samples), default=0)
memory_pass = 0 < peaks['app'] <= 384 * 1024 and 0 < peaks['renderer'] <= 512 * 1024 and combined <= 768 * 1024
success = success and memory_pass and result_files_pass
summary = dict(status='PASS' if success else 'FAIL', instrumentation=instrumentation, elapsed_s=round(time.monotonic() - start, 2),
               sample_count=len(samples), peak_pss_kib=peaks, peak_combined_pss_kib=combined,
               sampled_memory_limits_pass=memory_pass)
(out / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(raw[-3500:], flush=True)
print(json.dumps(summary, indent=2), flush=True)
sys.exit(0 if success else 1)
