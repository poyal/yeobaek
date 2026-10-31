"""Shared host-side checks. Importing this module never contacts or changes a device."""
from collections import Counter
import hashlib
from pathlib import Path
import re
import shlex
import subprocess
import uuid

TEST_AVDS = frozenset({'yeobaek-release-040', 'yeobaek-release-api30-040', 'yeobaek-soak-040'})


def sha256_file(path):
    """Hash large APKs/fixtures with a bounded buffer on memory-constrained hosts."""
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def owned_processes(process_list, services, package):
    """Include isolated WebView renderers owned by this app, not other apps' renderers."""
    running = {}
    for line in process_list.splitlines()[1:]:
        fields = line.split()
        if len(fields) == 2 and fields[0].isdigit():
            running[fields[0]] = fields[1]
    selected = {pid: name for pid, name in running.items() if name == package or name.startswith(package + ':')}
    for block in re.split(r'^\s*\* ServiceRecord\{', services, flags=re.M)[1:]:
        owner = re.search(r'^\s*packageName=(\S+)\s*$', block, re.M)
        process = re.search(r'^\s*app=ProcessRecord\{\S+ (\d+):([^/]+)/', block, re.M)
        if owner and owner[1] == package and process and running.get(process[1]) == process[2]:
            selected[process[1]] = process[2]
    return selected


def stage_private_fixture(adb, serial, package, source, destination):
    """Binary-safe on Windows: adb shell stdin can stop at a Ctrl-Z byte."""
    if package != 'com.poyal.yeobaek.debug' or not re.fullmatch(r'files/(pdf|image)-load/[^/]+', destination) or '..' in destination:
        raise ValueError('Unexpected private fixture destination')
    temporary = '/data/local/tmp/yeobaek-load-' + uuid.uuid4().hex
    prefix = [str(adb), '-s', serial]
    try:
        subprocess.run([*prefix, 'push', str(source), temporary], check=True, stdout=subprocess.DEVNULL)
        subprocess.run([*prefix, 'shell', 'run-as', package, 'cp', shlex.quote(temporary), shlex.quote(destination)], check=True)
    finally:
        subprocess.run([*prefix, 'shell', 'rm', '-f', temporary], check=True)


def require_test_device(serial, avd, expected_avd=None):
    if not re.fullmatch(r'emulator-\d+', serial) or avd not in TEST_AVDS:
        raise ValueError(f'Protected device: {serial}/{avd}; use a named verification AVD')
    if expected_avd is not None and avd != expected_avd:
        raise ValueError(f'AVD mismatch: expected {expected_avd}, got {avd}')


def expected_tests(root, source_set, selections):
    result = []
    for selected in selections:
        cls, _, method = selected.partition('#')
        source = Path(root) / source_set / 'java' / (cls.replace('.', '/') + '.kt')
        methods = re.findall(r'@Test\s+fun\s+(\w+)', source.read_text(encoding='utf-8'))
        if method and method not in methods:
            raise ValueError(f'Unknown test: {selected}')
        result.extend(f'{cls}#{name}' for name in ([method] if method else methods))
    if not result or len(set(result)) != len(result):
        raise ValueError('The selected test list is empty or duplicated')
    return result


def instrumentation_result(raw, expected, exit_code=0):
    """Require one start AND one success per test, JUnit success, and normal runner exit."""
    started, passed, failed, skipped = [], [], [], []
    fields = {}
    for line in raw.splitlines():
        field = re.match(r'INSTRUMENTATION_STATUS: (class|test)=(.*)', line)
        if field:
            fields[field[1]] = field[2].strip()
        status = re.fullmatch(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line.strip())
        if status:
            code = int(status[1])
            if 'class' in fields and 'test' in fields:
                name = fields['class'] + '#' + fields['test']
                if code == 1:
                    started.append(name)
                elif code == 0:
                    passed.append(name)
                elif code in (-3, -4):
                    skipped.append(name)
                else:
                    failed.append(name)
            elif code in (-1, -2, -3, -4):
                failed.append('<unidentified test>')
            fields = {}
    expected = set(expected)
    observed = set(started + passed + failed + skipped)
    duplicate = sorted({name for values in (started, passed) for name, count in Counter(values).items() if count > 1})
    missing = sorted(expected - observed)
    incomplete = sorted((expected & observed) - set(passed + failed + skipped))
    unexpected = sorted(observed - expected)
    junit = re.findall(r'^OK \((\d+) tests?\)\s*$', raw, re.M)
    normal = re.findall(r'^INSTRUMENTATION_CODE: (-?\d+)\s*$', raw, re.M) == ['-1']
    error = bool(re.search(r'FAILURES!!!|Process crashed|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|INSTRUMENTATION_RESULT: shortMsg=', raw))
    success = (exit_code == 0 and normal and not error and junit == [str(len(expected))]
               and not (failed or skipped or duplicate or missing or incomplete or unexpected)
               and Counter(started) == Counter(passed) == Counter(expected))
    return dict(status='PASS' if success else 'FAIL', exit_code=exit_code,
                normal_termination=normal, expected_tests=len(expected), passed_tests=len(passed),
                tests=sorted(observed), failed=failed, skipped=skipped, duplicate=duplicate,
                missing=missing, incomplete=incomplete, unexpected=unexpected)


if __name__ == '__main__':
    import argparse
    import json
    import sys
    parser = argparse.ArgumentParser()
    parser.add_argument('--log', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--exit-code', type=int, default=0)
    parser.add_argument('--selection', action='append', required=True, help='source/set:qualified.Class')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    names = []
    for selection in args.selection:
        source, cls = selection.split(':', 1)
        names.extend(expected_tests(root, source, [cls]))
    result = instrumentation_result(args.log.read_text(encoding='utf-8-sig'), names, args.exit_code)
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    sys.exit(0 if result['status'] == 'PASS' else 1)
