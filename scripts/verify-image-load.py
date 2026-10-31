#!/usr/bin/env python3
"""Opt-in image stress suite. Stages one fixture at a time on a dedicated test AVD."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import time
from verification import expected_tests, instrumentation_result, require_test_device, stage_private_fixture

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)
parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--fixtures', type=Path, default=ROOT / '.tools/image-load-fixtures')
parser.add_argument('--only', nargs='*')
parser.add_argument('--test-apk', type=Path, default=ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
args = parser.parse_args()
adb = os.environ.get('ADB', str(ROOT / '.tools/android-sdk/platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')))
package = 'com.poyal.yeobaek.debug'


def command(*parts, **kwargs):
    if kwargs.get('text'): kwargs.setdefault('encoding', 'utf-8')
    return subprocess.run([adb, '-s', args.serial, *parts], check=True, **kwargs)


def output(*parts):
    return command(*parts, capture_output=True, text=True).stdout.strip()


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''): digest.update(chunk)
    return digest.hexdigest()


avd = output('emu', 'avd', 'name').splitlines()[0]
require_test_device(args.serial, avd)
fixtures = json.loads((args.fixtures / 'manifest.json').read_text())
catalog = {f['file']: f for f in fixtures['fixtures']}
cases = args.only or [*catalog, 'folder-1000', 'folder-10000']
assert all(case in catalog or case in ['folder-1000', 'folder-10000'] for case in cases)
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
evidence = ROOT / '.tools/verification' / f'{stamp}-{args.serial}-image-load'
evidence.mkdir(parents=True)
apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
test_apk = args.test_apk
metadata = dict(serial=args.serial, avd=avd, api=output('shell', 'getprop', 'ro.build.version.sdk'),
                fingerprint=output('shell', 'getprop', 'ro.build.fingerprint'),
                apk_sha256=sha(apk), test_apk_sha256=sha(test_apk), fixtures=fixtures, cases=cases,
                thresholds=dict(app_pss_mib=384, worker_pss_mib=512, combined_pss_mib=768,
                                late_growth_mib=96, fd_growth=24,
                                case_timeout_seconds=1200, viewport_timeout_seconds=90))
(evidence / 'manifest.json').write_text(json.dumps(metadata, indent=2) + '\n')
for file in [apk, test_apk]: command('install', '-r', str(file), stdout=subprocess.DEVNULL)
output('shell', 'run-as', package, 'mkdir', '-p', 'files/image-load', 'files/image-load-results')
print(f'Evidence: {evidence}', flush=True)
reports = []
for name in cases:
    fixture = catalog.get(name)
    if fixture:
        local = args.fixtures / name
        assert local.stat().st_size == fixture['bytes'] and sha(local) == fixture['sha256']
        stage_private_fixture(adb, args.serial, package, local, f'files/image-load/{name}')
        assert output('shell', 'run-as', package, 'sha256sum', f'files/image-load/{name}').split()[0] == fixture['sha256']
    log_path = evidence / f'{name}.log'
    samples = []
    start = time.monotonic()
    print(f'Running {name}', flush=True)
    with log_path.open('w', encoding='utf-8') as log:
        process = subprocess.Popen([adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
            'com.poyal.yeobaek.ImageLoadTest', '-e', 'imageFixture', name,
            package + '.test/androidx.test.runner.AndroidJUnitRunner'], stdout=log, stderr=subprocess.STDOUT)
        while process.poll() is None:
            if time.monotonic() - start > 1200:
                command('shell', 'am', 'force-stop', package); process.terminate(); raise RuntimeError(f'{name} timed out')
            row = dict(elapsed_s=round(time.monotonic() - start, 3), app_pss_kib=0, worker_pss_kib=0)
            listing_result = subprocess.run([adb, '-s', args.serial, 'shell', 'ps', '-A', '-o', 'PID,NAME'], capture_output=True, text=True, encoding='utf-8')
            if listing_result.returncode:
                print(f'Telemetry retry: {listing_result.stderr.strip()}', flush=True)
                time.sleep(2)
                continue
            listing = listing_result.stdout
            for line in listing.splitlines():
                fields = line.strip().split()
                if len(fields) < 2: continue
                pid, proc_name = fields[:2]
                kind = 'app' if proc_name == package else 'worker' if proc_name.startswith(package + ':image') else None
                if not kind: continue
                result = subprocess.run([adb, '-s', args.serial, 'shell', 'dumpsys', 'meminfo', pid], capture_output=True, text=True, encoding='utf-8')
                match = re.search(r'TOTAL PSS:\s*(\d+)', result.stdout) or re.search(r'\n\s*TOTAL\s+(\d+)', result.stdout)
                if match: row[kind + '_pss_kib'] += int(match.group(1))
            samples.append(row)
            time.sleep(2)
    text = log_path.read_text(encoding='utf-8')
    (evidence / f'{name}.memory.json').write_text(json.dumps(samples, indent=2) + '\n')
    if fixture:
        assert output('shell', 'run-as', package, 'sha256sum', f'files/image-load/{name}').split()[0] == fixture['sha256'], 'Source was modified'
        output('shell', 'run-as', package, 'rm', '-f', f'files/image-load/{name}')
    result = instrumentation_result(text, expected_tests(ROOT, 'app/src/imageLoadTest', ['com.poyal.yeobaek.ImageLoadTest']), process.returncode)
    (evidence / f'{name}.instrumentation.json').write_text(json.dumps(result, indent=2))
    assert result['status'] == 'PASS', result
    report = json.loads(output('shell', 'run-as', package, 'cat', f'files/image-load-results/{name}.json'))
    assert report.get('passed'), report
    report['app_peak_mib'] = round(max(s['app_pss_kib'] for s in samples) / 1024, 2)
    report['worker_peak_mib'] = round(max(s['worker_pss_kib'] for s in samples) / 1024, 2)
    report['combined_peak_mib'] = round(max(s['app_pss_kib'] + s['worker_pss_kib'] for s in samples) / 1024, 2)
    assert report['app_peak_mib'] > 0 and report['worker_peak_mib'] > 0, 'Missing process memory samples'
    assert report['app_peak_mib'] <= 384 and report['worker_peak_mib'] <= 512 and report['combined_peak_mib'] <= 768, report
    assert len(report['cycles']) == 30 and len(report['reopens']) == 10
    early, late = report['cycles'][5:15], report['cycles'][20:30]
    report['late_growth_mib'] = round((statistics.median(x['pss_kib'] for x in late) - statistics.median(x['pss_kib'] for x in early)) / 1024, 2)
    report['fd_growth'] = max(x['fd_count'] for x in late) - max(x['fd_count'] for x in early)
    assert report['late_growth_mib'] <= 96 and report['fd_growth'] <= 24, report
    (evidence / f'{name}.json').write_text(json.dumps(report, indent=2) + '\n')
    reports.append(report)
    print(f"PASS {name}: first {report['first_display_ms']}ms, PSS {report['app_peak_mib']}/{report['worker_peak_mib']}MiB", flush=True)
(evidence / 'summary.json').write_text(json.dumps(reports, indent=2) + '\n')
