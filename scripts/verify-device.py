#!/usr/bin/env python3
"""Runs destructive test setup ONLY on a dedicated yeobaek emulator; keeps raw evidence."""
import argparse, datetime, hashlib, json, os, pathlib, re, shlex, subprocess, sys, time
from verification import expected_tests, instrumentation_result, owned_processes, require_test_device, sha256_file

ROOT = pathlib.Path(__file__).resolve().parent.parent
os.chdir(ROOT)
p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--suite', choices=['engine', 'native', 'e2e', 'epub', 'recovery', 'restore', 'about', 'journeys', 'backup-settings', 'external', 'stability', 'load', 'soak', 'repeat', 'macro', 'formats-macro', 'profile'], required=True)
p.add_argument('--theme', choices=['LIGHT', 'DARK'], default='LIGHT')
p.add_argument('--variant', choices=['debug', 'benchmark', 'release', 'profile'], default='debug')
p.add_argument('--method')
p.add_argument('--test-class', help='Class name within the selected test module; checked against source')
p.add_argument('--expected-avd')
p.add_argument('--argument', action='append', default=[], metavar='NAME=VALUE')
p.add_argument('--output-parent', type=pathlib.Path, default=ROOT / '.tools/verification')
p.add_argument('--apk', type=pathlib.Path)
p.add_argument('--test-apk', type=pathlib.Path)
p.add_argument('--timeout', type=int, default=14400)
args = p.parse_args()
native = args.suite in ('engine', 'native')
macro = args.suite in ('macro', 'formats-macro', 'profile')
required_variant = {'engine':'debug', 'native':'debug', 'macro':'benchmark', 'formats-macro':'benchmark', 'profile':'profile'}.get(args.suite)
if required_variant and args.variant != required_variant:
    p.error(f'{args.suite}에는 --variant {required_variant}가 필요합니다.')
adb = os.environ.get('ADB', str(ROOT / '.tools/android-sdk/platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')))
def command(*parts, **kwargs):
    if kwargs.get('text'):
        kwargs.setdefault('encoding', 'utf-8')
    return subprocess.run([adb, '-s', args.serial, *parts], check=True, **kwargs)
def output(*parts):
    return command(*parts, capture_output=True, text=True).stdout.strip()
avd = output('emu', 'avd', 'name').splitlines()[0]
require_test_device(args.serial, avd, args.expected_avd)
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f')
out = args.output_parent / f'{stamp}-{args.serial}-{args.suite}-{args.theme}'
out.mkdir(parents=True, exist_ok=False)
pkg = 'com.poyal.yeobaek' + (f'.{args.variant}' if args.variant != 'release' else '')
apk = args.apk or ROOT / f'app/build/outputs/apk/{args.variant}/app-{args.variant}.apk'
test_apk = args.test_apk or ROOT / ('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk' if native
    else 'benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk' if macro
    else 'e2e/build/outputs/apk/debug/e2e-debug.apk')
manifest = dict(vars(args), avd=avd, package=pkg, apk_sha256=sha256_file(apk),
    fresh_install=macro,
    test_apk_sha256=sha256_file(test_apk),
    api=output('shell', 'getprop', 'ro.build.version.sdk'),
    abi=output('shell', 'getprop', 'ro.product.cpu.abi'),
    fingerprint=output('shell', 'getprop', 'ro.build.fingerprint'),
    display=output('shell', 'wm', 'size'), density=output('shell', 'wm', 'density'),
    font_scale=output('shell', 'settings', 'get', 'system', 'font_scale'))
manifest['guest_mem_total'] = output('shell', 'head', '-n', '1', '/proc/meminfo')
manifest['cpu_online'] = output('shell', 'cat', '/sys/devices/system/cpu/online')
manifest['page_size'] = output('shell', 'getconf', 'PAGESIZE')
manifest['emulator_serialno'] = output('shell', 'getprop', 'ro.boot.serialno')
manifest['graphics'] = [line.strip() for line in output('shell', 'dumpsys', 'SurfaceFlinger').splitlines() if 'GLES:' in line]
manifest['input_cancel_supported'] = 'CANCEL' in output('shell', 'input')
manifest['source_commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
manifest['source_diff_sha256'] = hashlib.sha256(subprocess.check_output(['git', 'diff', '--binary'])).hexdigest()
manifest['untracked_source_sha256'] = {name: sha256_file(ROOT / name)
    for name in subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], text=True).splitlines() if (ROOT / name).is_file()}
manifest['fixture_sha256'] = {}
if args.suite in ('journeys', 'backup-settings', 'stability', 'formats-macro') or args.test_class in ('RecoveryFormatsJourneyTest', 'UpgradeJourneyTest'):
    fixture_manifest = ROOT / '.tools/fixtures/YeobaekQA/manifest.json'
    fixtures = json.loads(fixture_manifest.read_text(encoding='utf-8'))['fixtures']
    paths = ['/sdcard/Download/YeobaekQA/' + row['name'] for row in fixtures]
    actual = dict(line.split(None, 1)[::-1] for line in output('shell', 'sha256sum ' + ' '.join(map(shlex.quote, paths))).splitlines())
    actual = {name.strip(): digest for name, digest in actual.items()}
    for row, path in zip(fixtures, paths):
        if actual.get(path) != row['sha256']:
            raise ValueError(f'Synthetic source changed or missing: {row["name"]}')
    manifest['fixture_sha256'] = {row['name']: row['sha256'] for row in fixtures}
if macro or args.suite == 'load':
    fixtures = json.loads((ROOT / '.tools/fixtures/fixtures.json').read_text(encoding='utf-8'))
    # Include the large TXT and thousand-file list workloads as well as YeobaekQA.
    # Batch checks to stay below host/device command-line limits for Korean paths.
    for offset in range(0, len(fixtures), 100):
        batch = fixtures[offset:offset + 100]
        paths = ['/sdcard/Download/Yeobaek/' + row['name'].replace('\\', '/') for row in batch]
        actual = dict(line.split(None, 1)[::-1] for line in output('shell', 'sha256sum ' + ' '.join(map(shlex.quote, paths))).splitlines())
        actual = {name.strip(): digest for name, digest in actual.items()}
        for row, path in zip(batch, paths):
            if actual.get(path) != row['sha256']:
                raise ValueError(f'Synthetic source changed or missing: {row["name"]}')
            manifest['fixture_sha256']['Yeobaek/' + row['name'].replace('\\', '/')] = row['sha256']
if macro:
    (out / 'display.txt').write_text(output('shell', 'dumpsys', 'display'), encoding='utf-8')
    (out / 'thermal.txt').write_text(output('shell', 'dumpsys', 'thermalservice'), encoding='utf-8')
    (out / 'battery.txt').write_text(output('shell', 'dumpsys', 'battery'), encoding='utf-8')
(out/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
if (int(manifest['api']) <= 30 and not (native or macro)) or args.suite == 'stability':
    # Android 11's external test output is inaccessible to shell after a reboot.
    # The dedicated AVD guard above prevents rooting or clearing a user's device.
    command('root')
    command('wait-for-device')
command('shell', 'mkdir', '-p', "'/sdcard/Download/Yeobaek/빈 폴더'")
command('shell', 'mkdir', '-p', '/sdcard/Download/YeobaekQA/Empty')
if macro and 'package:' + pkg in output('shell', 'pm', 'list', 'packages', pkg).splitlines():
    # Baseline/candidate may have different versionCodes. Both begin with a clean
    # dedicated benchmark app; external synthetic fixtures remain in place.
    command('uninstall', pkg)
command('install', '-r', str(apk))
if native:
    command('install', '-r', str(test_apk))
    runner = 'com.poyal.yeobaek.debug.test/androidx.test.runner.AndroidJUnitRunner'
    classes = ['ReaderEngineTest', 'PersistenceTest'] if args.suite == 'engine' else [f.stem for f in sorted((ROOT / 'app/src/androidTest/java/com/poyal/yeobaek').glob('*Test.kt')) if f.stem != 'EpubSoakTest']
    options = ['-e', 'class', ','.join('com.poyal.yeobaek.' + cls for cls in classes)]
elif macro:
    command('install', '-r', str(test_apk))
    command('shell', 'rm', '-rf', '/sdcard/Android/media/com.poyal.yeobaek.benchmarks')
    runner = 'com.poyal.yeobaek.benchmarks/androidx.test.runner.AndroidJUnitRunner'
    options = ['-e', 'androidx.benchmark.suppressErrors', 'EMULATOR', '-e', 'class',
        'com.poyal.yeobaek.benchmarks.' + {'profile':'ReaderProfile', 'macro':'ReaderBenchmark', 'formats-macro':'FormatsBenchmark'}[args.suite] + (f'#{args.method}' if args.method else '')]
else:
    command('install', '-r', str(test_apk))
    runner = 'com.poyal.yeobaek.e2e/androidx.test.runner.AndroidJUnitRunner'
    klass = {'e2e':'ReaderJourneyTest', 'epub':'EpubJourneyTest', 'recovery':'RecoveryJourneyTest', 'restore':'RestoreJourneyTest', 'about':'AboutJourneyTest', 'journeys':'FullJourneyTest', 'backup-settings':'BackupSettingsJourneyTest', 'external':'ExternalOpenTest', 'stability':'StabilityJourneyTest'}.get(args.suite, 'PerformanceJourneyTest')
    method = args.method or dict(load='largeFilesAndLongParagraph', soak='continuousReading30Minutes', repeat='repeatedOpen100Times').get(args.suite)
    options = ['-e', 'targetPackage', pkg, '-e', 'theme', args.theme, '-e', 'class',
        f'com.poyal.yeobaek.e2e.{klass}' + (f'#{method}' if method else '')]
    command('shell', 'rm', '-rf', *('/sdcard/Android/data/com.poyal.yeobaek.e2e/files/' + name
        for name in ('captures', 'performance', 'about', 'journeys', 'restore', 'reader-upgrade')))
if args.test_class:
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9]*', args.test_class):
        p.error('Invalid test class')
    prefix = 'com.poyal.yeobaek.' + ('benchmarks.' if macro else '' if native else 'e2e.')
    options[options.index('class') + 1] = prefix + args.test_class + (f'#{args.method}' if args.method else '')
if macro:
    options.extend(['-e', 'theme', args.theme])
    command('shell', 'rm', '-rf', '/sdcard/Android/data/com.poyal.yeobaek.benchmarks/files/journeys')
requested_classes = options[options.index('class') + 1].split(',')
options.extend(['-e', 'verifiedAvd', avd])
module = 'app/src/androidTest' if native else 'benchmark/src/main' if macro else 'e2e/src/main'
expected_names = expected_tests(ROOT, module, requested_classes)
(out/'expected-tests.json').write_text(json.dumps(expected_names, indent=2), encoding='utf-8')
for argument in args.argument:
    name, separator, value = argument.partition('=')
    if not separator or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*', name) or name in ('class', 'targetPackage', 'verifiedAvd'):
        p.error(f'Invalid instrumentation argument: {argument}')
    options.extend(['-e', name, value])
original = {(table, name): output('shell', 'settings', 'get', table, name) for table, name in
            [('system', 'font_scale'), ('system', 'accelerometer_rotation'), ('system', 'user_rotation'), ('secure', 'show_ime_with_hard_keyboard')]}
print(f'Evidence: {out}', flush=True)
exit_code = -1
def sample_resources(elapsed):
    processes = []
    # WebView's isolated renderer is named after its provider, not the reading app.
    # ActivityManager's service owner prevents counting another app's WebView.
    process_list = output('shell', 'ps', '-A', '-o', 'PID,NAME')
    services = output('shell', 'dumpsys', 'activity', 'services', pkg)
    for pid, name in owned_processes(process_list, services, pkg).items():
        memory = output('shell', 'dumpsys', 'meminfo', pid)
        sample = dict(pid=int(pid), process=name)
        for key, pattern in [('pss_kib', r'TOTAL PSS:\s*(\d+)'), ('java_heap_kib', r'Java Heap:\s*(\d+)'), ('native_heap_kib', r'Native Heap:\s*(\d+)')]:
            match = re.search(pattern, memory)
            sample[key] = int(match[1]) if match else None
        fds = subprocess.run([adb, '-s', args.serial, 'shell', f'ls -1 /proc/{pid}/fd'], capture_output=True, text=True, encoding='utf-8')
        sample['fd_count'] = len(fds.stdout.splitlines()) if fds.returncode == 0 else None
        threads = re.search(r'^Threads:\s*(\d+)', output('shell', 'cat', f'/proc/{pid}/status'), re.M)
        sample['threads'] = int(threads[1]) if threads else None
        processes.append(sample)
    cache = subprocess.run([adb, '-s', args.serial, 'shell', 'du', '-sk', f'/data/user/0/{pkg}/cache'], capture_output=True, text=True, encoding='utf-8')
    row = dict(elapsed_s=round(elapsed, 2), processes=processes,
               cache_kib=int(cache.stdout.split()[0]) if cache.returncode == 0 and cache.stdout.split() else None)
    with (out / 'resources.ndjson').open('a', encoding='utf-8') as stream:
        stream.write(json.dumps(row) + '\n')
try:
    # An interrupted UiAutomator gesture can leave pointer 0 down in Android 16's
    # InputDispatcher. Cancel it before a new runner starts, never during a test.
    if manifest['input_cancel_supported']:
        command('shell', 'input', 'touchscreen', 'motionevent', 'CANCEL', '0', '0')
    with (out/'instrumentation.txt').open('w', encoding='utf-8') as log:
        try:
            process = subprocess.Popen([adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r', *options, runner], stdout=log, stderr=subprocess.STDOUT)
            start = time.monotonic(); next_sample = 0
            while process.poll() is None:
                elapsed = time.monotonic() - start
                if elapsed >= args.timeout:
                    process.terminate()
                    raise subprocess.TimeoutExpired(process.args, args.timeout)
                if args.suite == 'stability' and elapsed >= next_sample:
                    try:
                        sample_resources(elapsed)
                    except (subprocess.CalledProcessError, ValueError) as error:
                        with (out / 'resource-errors.txt').open('a', encoding='utf-8') as stream: stream.write(str(error) + '\n')
                    next_sample += 60
                time.sleep(1)
            exit_code = process.returncode
        except subprocess.TimeoutExpired:
            log.write('\nINSTRUMENTATION_FAILED: host timeout\n')
            command('shell', 'am', 'force-stop', pkg)
            command('shell', 'am', 'force-stop', runner.split('/')[0])
            process.wait(timeout=30)
        except KeyboardInterrupt:
            log.write('\nINSTRUMENTATION_ABORTED: host interrupted\n')
            command('shell', 'am', 'force-stop', pkg)
            command('shell', 'am', 'force-stop', runner.split('/')[0])
            process.terminate()
            process.wait(timeout=30)
finally:
    if manifest['input_cancel_supported']:
        command('shell', 'input', 'touchscreen', 'motionevent', 'CANCEL', '0', '0')
    for (table, name), value in original.items():
        command('shell', 'settings', 'delete', table, name) if value == 'null' else command('shell', 'settings', 'put', table, name, value)
    for setting, original_value in [('size', manifest['display']), ('density', manifest['density'])]:
        override = re.search(r'Override (?:size|density):\s*(\S+)', original_value)
        command('shell', 'wm', setting, override[1] if override else 'reset')
raw = (out/'instrumentation.txt').read_text(encoding='utf-8')
summary = instrumentation_result(raw, expected_names, exit_code)
success = summary['status'] == 'PASS'
(out/'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
if not (native or macro):
    if int(manifest['api']) <= 30:
        command('root')
        command('wait-for-device')
    command('pull', '/sdcard/Android/data/com.poyal.yeobaek.e2e/files', str(out/'device'))
if macro:
    command('pull', '/sdcard/Android/media/com.poyal.yeobaek.benchmarks', str(out/'benchmark'))
    if args.suite in ('macro', 'formats-macro'):
        command('pull', '/sdcard/Android/data/com.poyal.yeobaek.benchmarks/files/journeys', str(out/'journeys'))
print(raw[-5000:], flush=True)
sys.exit(0 if success else 1)
