#!/usr/bin/env python3
"""Published release -> candidate -> reboot, only on explicitly named test AVDs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from verification import require_test_device, sha256_file

ROOT = Path(__file__).resolve().parent.parent
p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--from-apk', type=Path, required=True)
p.add_argument('--from-sha256', required=True)
p.add_argument('--to-apk', type=Path, default=ROOT / 'app/build/outputs/apk/release/app-release.apk')
p.add_argument('--output-parent', type=Path, default=ROOT / '.tools/verification')
p.add_argument('--reboot-mode', choices=['adb', 'external'], default='adb',
    help='external waits for the host to cold-boot the same dedicated AVD without wiping its data')
args = p.parse_args()
adb = os.environ.get('ADB', str(ROOT / '.tools/android-sdk/platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')))
def adb_output(*parts, timeout=30):
    return subprocess.check_output([adb, '-s', args.serial, *parts], encoding='utf-8', timeout=timeout).strip()
avd = adb_output('emu', 'avd', 'name').splitlines()[0]
require_test_device(args.serial, avd)
assert sha256_file(args.from_apk) == args.from_sha256.lower(), 'Published APK hash mismatch'
out = args.output_parent / f'upgrade-{time.time_ns()}-{args.serial}'
out.mkdir(parents=True)
package = 'com.poyal.yeobaek'

def snapshot(label):
    """Copy only the synthetic app's persisted state while its process is stopped."""
    adb_output('shell', 'am', 'force-stop', package)
    location = out / label
    location.mkdir()
    for child in ('databases', 'files/datastore'):
        destination = location / Path(child).name
        subprocess.run([adb, '-s', args.serial, 'pull',
            f'/data/user/0/{package}/{child}', str(destination)], check=True, capture_output=True)
    with sqlite3.connect(location / 'databases/library.db') as connection:
        connection.row_factory = sqlite3.Row
        assert connection.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
        schema = [dict(row) for row in connection.execute(
            'SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name')]
        tables = {}
        for row in schema:
            if row['type'] == 'table':
                quoted = '"' + row['name'].replace('"', '""') + '"'
                values = [dict(value) for value in connection.execute('SELECT * FROM ' + quoted)]
                tables[row['name']] = sorted(values, key=lambda value: json.dumps(value, sort_keys=True))
        version = connection.execute('PRAGMA user_version').fetchone()[0]
    preferences = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted((location / 'datastore').glob('*.preferences_pb'))}
    assert {'app_preferences.preferences_pb', 'reader_settings.preferences_pb'} <= preferences.keys()
    grants_path = '/data/system/urigrants.xml'
    grants_xml = subprocess.check_output([adb, '-s', args.serial, 'exec-out', 'cat', grants_path])
    grants_format = 'ABX' if grants_xml.startswith(b'ABX\x00') else 'XML'
    if grants_format == 'ABX':
        # Modern Android persists this file as binary XML. Convert to stdout;
        # never use abx2xml -i, which would modify the system's original file.
        grants_xml = subprocess.check_output([adb, '-s', args.serial, 'shell', 'abx2xml', grants_path, '-'])
    grants = [dict(element.attrib) for element in ET.fromstring(grants_xml)
        if element.get('targetPkg') == package]
    assert grants, 'Missing persisted SAF permission'
    data = dict(schema_version=version, schema=schema, tables=tables,
        preferences_sha256=preferences, persisted_uri_grants=grants,
        persisted_uri_grants_format=grants_format)
    (location / 'snapshot.json').write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    return data

def assert_records_preserved(before, after):
    # Opening a book legitimately updates openedAt and the active position/session.
    # Identity, encoding, content version and every bookmark must remain unchanged.
    def books(data):
        return sorted([{key: value for key, value in row.items() if key != 'openedAt'}
            for row in data['tables']['books']], key=lambda row: row['id'])
    assert books(before) == books(after), 'Book identity/encoding/content changed'
    for name in ('bookmarks', 'pdf_bookmarks', 'image_bookmarks', 'epub_bookmarks'):
        assert before['tables'][name] == after['tables'][name], f'{name} changed'
    assert before['persisted_uri_grants'] == after['persisted_uri_grants'], 'SAF grants changed'
def journey(apk, method):
    subprocess.run([sys.executable, str(ROOT / 'scripts/verify-device.py'), '--serial', args.serial,
        '--expected-avd', avd, '--suite', 'e2e', '--variant', 'release', '--theme', 'DARK', '--test-class', 'UpgradeJourneyTest',
        '--method', method, '--apk', str(apk), '--output-parent', str(out)], check=True)
def hashes():
    return sorted(adb_output('shell', 'find /sdcard/Download/YeobaekQA -type f -exec sha256sum {} +').splitlines())
# The previous candidate may have a higher versionCode than the published source.
# Only the dedicated synthetic app is removed; prepare recreates the old-version
# records and grants through the real UI before the over-install being tested.
installed = adb_output('shell', 'pm', 'list', 'packages', 'com.poyal.yeobaek').splitlines()
if 'package:com.poyal.yeobaek' in installed:
    adb_output('uninstall', 'com.poyal.yeobaek')
# Root is restricted to the already-validated dedicated AVD. It permits a read-only
# snapshot of the release app, which deliberately does not enable run-as/debugging.
adb_output('root')
adb_output('wait-for-device')
assert adb_output('shell', 'id', '-u') == '0', 'Dedicated AVD must support rooted snapshots'
journey(args.from_apk, 'prepareFourFormatRecords')
original = hashes()
before_install = snapshot('published-before-install')
assert len(before_install['tables']['books']) == 4, 'Expected exactly four prepared books'
formats = {row['format'] for row in before_install['tables']['books']}
assert formats == {'TXT', 'PDF', 'IMAGE_ARCHIVE', 'EPUB'}, formats
assert all(row['id'] and row['version'] for row in before_install['tables']['books'])
assert next(row for row in before_install['tables']['books'] if row['format'] == 'TXT')['charset']
for table in ('positions', 'pdf_positions', 'image_positions', 'epub_positions',
              'bookmarks', 'pdf_bookmarks', 'image_bookmarks', 'epub_bookmarks'):
    assert before_install['tables'][table], f'Preparation did not create {table}'
assert before_install['tables']['session'][0]['bookId'], 'Missing resume candidate'
adb_output('install', '-r', str(args.to_apk.resolve()), timeout=120)
after_install = snapshot('candidate-before-first-launch')
assert before_install == after_install, 'Over-install changed persisted records or settings'
journey(args.to_apk, 'verifyFourFormatRecordsAndLeaveResumeCandidate')
assert original == hashes(), 'External originals changed during upgrade'
after_launch = snapshot('candidate-after-reading')
assert_records_preserved(before_install, after_launch)
before_boot = adb_output('shell', 'cat', '/proc/sys/kernel/random/boot_id')
if args.reboot_mode == 'external':
    # Emulator console shutdown stops the VM, bypassing Android's orderly
    # shutdown sync. Flush the stopped app's persisted files before the host
    # cold boot; this scenario does not claim sudden-power-loss durability.
    adb_output('shell', 'sync', timeout=60)
(out / 'reboot-request.json').write_text(json.dumps(dict(avd=avd, serial=args.serial,
    before_boot=before_boot, mode=args.reboot_mode, guest_filesystem_synced=args.reboot_mode=='external',
    created_unix=time.time()), indent=2), encoding='utf-8')
print(f'Reboot checkpoint ({args.reboot_mode}): {out}', flush=True)
if args.reboot_mode == 'adb':
    adb_output('reboot', timeout=20)
deadline = time.monotonic() + 180
while True:
    if time.monotonic() >= deadline: raise TimeoutError('Reboot did not complete with a new boot ID')
    try:
        subprocess.run([adb, '-s', args.serial, 'wait-for-device'], timeout=30, check=True, capture_output=True)
        after_boot = adb_output('shell', 'cat', '/proc/sys/kernel/random/boot_id', timeout=5)
        if after_boot != before_boot and adb_output('shell', 'getprop', 'sys.boot_completed', timeout=5) == '1':
            break
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        pass  # Transport may disappear between wait-for-device and the shell command.
    time.sleep(1)
adb_output('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
adb_output('shell', 'wm', 'dismiss-keyguard')
journey(args.to_apk, 'verifyFourFormatRecordsAndLeaveResumeCandidate')
assert original == hashes(), 'External originals changed after reboot'
adb_output('root')
adb_output('wait-for-device')
after_reboot = snapshot('candidate-after-reboot-reading')
assert_records_preserved(before_install, after_reboot)
(out / 'summary.json').write_text(json.dumps(dict(status='PASS', avd=avd,
    from_apk_sha256=args.from_sha256, to_apk_sha256=sha256_file(args.to_apk),
    actual_reboot=True, before_boot=before_boot, after_boot=after_boot, source_hashes=original,
    reboot_method='adb reboot' if args.reboot_mode == 'adb' else 'guest filesystem sync followed by external cold boot of the same AVD without data wipe',
    persisted_state_identical_before_first_launch=True,
    book_identity_encoding_content_and_bookmarks_preserved_after_reading_and_reboot=True,
    snapshot_stop_method='am force-stop after UI save and 1500 ms on home',
    schema_version=before_install['schema_version'],
    record_counts={name: len(rows) for name, rows in before_install['tables'].items()},
    preferences_sha256=before_install['preferences_sha256'],
    persisted_uri_grants_format=before_install['persisted_uri_grants_format'],
    persisted_uri_grants=before_install['persisted_uri_grants']), indent=2), encoding='utf-8')
print(out)
