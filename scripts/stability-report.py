#!/usr/bin/env python3
"""Assess completed per-format soak/reopen journeys and independent host telemetry."""
import argparse
import json
from pathlib import Path
import statistics


def report(run):
    instrumentation = json.loads((run / 'summary.json').read_text(encoding='utf-8'))
    manifest = json.loads((run / 'manifest.json').read_text(encoding='utf-8'))
    resources = [json.loads(line) for line in (run / 'resources.ndjson').read_text(encoding='utf-8').splitlines()]
    formats = {}
    for source in (run / 'device/journeys').rglob('*-result.json'):
        data = json.loads(source.read_text(encoding='utf-8'))
        kind = source.name.removesuffix('-result.json')
        pid = int(data['pid'])
        rows = [row for row in resources if any(p['pid'] == pid for p in row['processes'])]
        values = []
        for row in rows:
            app = next(p for p in row['processes'] if p['pid'] == pid)
            workers = [p for p in row['processes'] if p['process'] != manifest['package']]
            values.append(dict(elapsed_s=row['elapsed_s'], app_pss_mib=(app['pss_kib'] or 0) / 1024,
                workers_pss_mib=sum(p['pss_kib'] or 0 for p in workers) / 1024,
                worker_processes=[p['process'] for p in workers],
                workers_complete=all(p['pss_kib'] is not None for p in workers),
                fd_count=app['fd_count'], threads=app['threads'], cache_kib=row['cache_kib']))
        row = dict(journey=data, telemetry_samples=len(values), measurements=values)
        long_enough = data['actual_soak_ms'] >= 1_800_000 and data['reopens'] >= 100
        complete = len(values) >= 20 and all(v['app_pss_mib'] > 0 and v['workers_complete'] and v['fd_count'] is not None and v['threads'] is not None for v in values)
        if kind == 'epub':
            # A provider-named WebView renderer must not silently disappear from totals.
            complete = complete and any('sandboxed_process' in name for v in values for name in v['worker_processes'])
        if complete:
            early, late = values[2:7], values[-5:]
            row.update(app_peak_mib=max(v['app_pss_mib'] for v in values),
                workers_peak_mib=max(v['workers_pss_mib'] for v in values),
                combined_peak_mib=max(v['app_pss_mib'] + v['workers_pss_mib'] for v in values),
                late_growth_mib=statistics.median(v['app_pss_mib'] for v in late) - statistics.median(v['app_pss_mib'] for v in early),
                fd_growth=max(v['fd_count'] for v in late) - max(v['fd_count'] for v in early),
                thread_growth=max(v['threads'] for v in late) - max(v['threads'] for v in early))
            row['resource_limits_pass'] = (row['app_peak_mib'] <= 384 and row['workers_peak_mib'] <= 512
                and row['combined_peak_mib'] <= 768 and row['late_growth_mib'] <= 96 and row['fd_growth'] <= 24)
        row['status'] = 'SMOKE_ONLY' if not long_enough else 'MISSING_TELEMETRY' if not complete else (
            'PASS' if row['resource_limits_pass'] else 'FAIL')
        formats[kind] = row
    expected = instrumentation['expected_tests']
    passed = instrumentation['status'] == 'PASS' and len(formats) == expected and all(row['status'] == 'PASS' for row in formats.values())
    return dict(status='PASS' if passed else 'INCOMPLETE_OR_FAILED', run=str(run), formats=formats,
        note='Per-format process PSS and host FD samples. Threads and cache growth are observations, not evidence of a leak by themselves. '
             'Short smoke runs cannot establish long-duration stability. Emulator diagnostics only.')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('run', type=Path)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    value = report(args.run)
    args.output.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    print(value['status'], args.output)
    raise SystemExit(0 if value['status'] == 'PASS' else 1)
