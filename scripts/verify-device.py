#!/usr/bin/env python3
"""Runs destructive test setup ONLY on a dedicated yeobaek emulator; keeps raw evidence."""
import argparse, datetime, hashlib, json, os, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
os.chdir(ROOT)
p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--suite', choices=['engine', 'e2e', 'epub', 'recovery', 'restore', 'about', 'load', 'soak', 'repeat', 'macro', 'profile'], required=True)
p.add_argument('--theme', choices=['LIGHT', 'DARK'], default='LIGHT')
p.add_argument('--variant', choices=['debug', 'benchmark', 'release', 'profile'], default='debug')
p.add_argument('--method')
args = p.parse_args()
required_variant = {'engine':'debug', 'macro':'benchmark', 'profile':'profile'}.get(args.suite)
if required_variant and args.variant != required_variant:
    p.error(f'{args.suite}에는 --variant {required_variant}가 필요합니다.')
adb = os.environ.get('ADB', str(ROOT / '.tools/android-sdk/platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb')))
def command(*parts, **kwargs):
    if kwargs.get('text'):
        kwargs.setdefault('encoding', 'utf-8')
    return subprocess.run([adb, '-s', args.serial, *parts], check=True, **kwargs)
def output(*parts):
    return command(*parts, capture_output=True, text=True).stdout.strip()
if not args.serial.startswith('emulator-'):
    sys.exit('이 스크립트는 데이터를 초기화하므로 전용 에뮬레이터에서만 실행합니다.')
avd = output('emu', 'avd', 'name').splitlines()[0]
if not avd.startswith('yeobaek-'):
    sys.exit(f'보호된 기기: {avd}. yeobaek-* 전용 AVD가 필요합니다.')
stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
out = ROOT / '.tools/verification' / f'{stamp}-{args.serial}-{args.suite}-{args.theme}'
out.mkdir(parents=True)
pkg = 'com.poyal.yeobaek' + (f'.{args.variant}' if args.variant != 'release' else '')
apk = ROOT / f'app/build/outputs/apk/{args.variant}/app-{args.variant}.apk'
test_apk = ROOT / ('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk' if args.suite == 'engine'
    else 'benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk' if args.suite in ('macro', 'profile')
    else 'e2e/build/outputs/apk/debug/e2e-debug.apk')
manifest = dict(vars(args), avd=avd, package=pkg, apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
    test_apk_sha256=hashlib.sha256(test_apk.read_bytes()).hexdigest(),
    api=output('shell', 'getprop', 'ro.build.version.sdk'),
    fingerprint=output('shell', 'getprop', 'ro.build.fingerprint'),
    display=output('shell', 'wm', 'size'), density=output('shell', 'wm', 'density'),
    font_scale=output('shell', 'settings', 'get', 'system', 'font_scale'))
(out/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
if int(manifest['api']) <= 30 and args.suite not in ('engine', 'macro', 'profile'):
    # Android 11's external test output is inaccessible to shell after a reboot.
    # The dedicated AVD guard above prevents rooting or clearing a user's device.
    command('root')
    command('wait-for-device')
command('shell', 'mkdir', '-p', "'/sdcard/Download/Yeobaek/빈 폴더'")
command('install', '-r', str(apk))
if args.suite == 'engine':
    command('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
    runner = 'com.poyal.yeobaek.debug.test/androidx.test.runner.AndroidJUnitRunner'
    options = ['-e', 'class', 'com.poyal.yeobaek.ReaderEngineTest,com.poyal.yeobaek.PersistenceTest']
elif args.suite in ('macro', 'profile'):
    command('install', '-r', 'benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk')
    command('shell', 'rm', '-rf', '/sdcard/Android/media/com.poyal.yeobaek.benchmarks')
    runner = 'com.poyal.yeobaek.benchmarks/androidx.test.runner.AndroidJUnitRunner'
    options = ['-e', 'androidx.benchmark.suppressErrors', 'EMULATOR', '-e', 'class',
        ('com.poyal.yeobaek.benchmarks.ReaderProfile' if args.suite == 'profile' else 'com.poyal.yeobaek.benchmarks.ReaderBenchmark') + (f'#{args.method}' if args.method else '')]
else:
    command('install', '-r', 'e2e/build/outputs/apk/debug/e2e-debug.apk')
    runner = 'com.poyal.yeobaek.e2e/androidx.test.runner.AndroidJUnitRunner'
    klass = {'e2e':'ReaderJourneyTest', 'epub':'EpubJourneyTest', 'recovery':'RecoveryJourneyTest', 'restore':'RestoreJourneyTest', 'about':'AboutJourneyTest'}.get(args.suite, 'PerformanceJourneyTest')
    method = args.method or dict(load='largeFilesAndLongParagraph', soak='continuousReading30Minutes', repeat='repeatedOpen100Times').get(args.suite)
    options = ['-e', 'targetPackage', pkg, '-e', 'theme', args.theme, '-e', 'class',
        f'com.poyal.yeobaek.e2e.{klass}' + (f'#{method}' if method else '')]
    command('shell', 'rm', '-rf', '/sdcard/Android/data/com.poyal.yeobaek.e2e/files/captures', '/sdcard/Android/data/com.poyal.yeobaek.e2e/files/performance', '/sdcard/Android/data/com.poyal.yeobaek.e2e/files/about')
print(f'Evidence: {out}', flush=True)
with (out/'instrumentation.txt').open('w', encoding='utf-8') as log:
    result = subprocess.run([adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r', *options, runner], stdout=log, stderr=subprocess.STDOUT)
raw = (out/'instrumentation.txt').read_text(encoding='utf-8')
match = re.search(r'OK \((\d+) tests?\)', raw)
expected = 1 if args.method else dict(engine=3, e2e=2, epub=3, recovery=4, macro=6).get(args.suite, 1)
actual = int(match.group(1)) if match else 0
requested_classes = options[options.index('class') + 1].split(',')
expected_names = set()
for requested in requested_classes:
    class_name, _, selected_method = requested.partition('#')
    if selected_method:
        expected_names.add(class_name + '#' + selected_method)
    else:
        module = 'app/src/androidTest' if args.suite == 'engine' else 'benchmark/src/main' if args.suite in ('macro', 'profile') else 'e2e/src/main'
        source = ROOT / module / 'java' / (class_name.replace('.', '/') + '.kt')
        expected_names.update(class_name + '#' + name for name in re.findall(r'@Test\s+fun\s+(\w+)', source.read_text(encoding='utf-8')))
actual_names = {cls + '#' + name for cls, name in re.findall(r'INSTRUMENTATION_STATUS: class=(\S+).*?INSTRUMENTATION_STATUS: test=(\S+)', raw, re.S)}
success = (result.returncode == 0 and actual == expected == len(expected_names) and actual_names == expected_names
    and 'FAILURES!!!' not in raw and 'Process crashed' not in raw and 'INSTRUMENTATION_STATUS_CODE: -3' not in raw)
summary = {'status': 'PASS' if success else 'FAIL', 'exit_code': result.returncode, 'expected_tests': expected, 'passed_tests': actual,
    'tests': sorted(actual_names), 'missing': sorted(expected_names - actual_names), 'unexpected': sorted(actual_names - expected_names)}
(out/'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
if args.suite not in ('engine', 'macro', 'profile'):
    if int(manifest['api']) <= 30:
        command('root')
        command('wait-for-device')
    command('pull', '/sdcard/Android/data/com.poyal.yeobaek.e2e/files', str(out/'device'))
if args.suite in ('macro', 'profile'):
    command('pull', '/sdcard/Android/media/com.poyal.yeobaek.benchmarks', str(out/'benchmark'))
print(raw[-5000:], flush=True)
sys.exit(0 if success else 1)
