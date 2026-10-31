#!/usr/bin/env python3
"""Compare complete, matching-environment benchmark runs; emulator diagnostics only."""
import argparse
import json
from pathlib import Path
from performance_metrics import distribution, regression, required_metrics


def read_run(path):
    meta = json.loads((path / 'manifest.json').read_text(encoding='utf-8'))
    result = json.loads((path / 'summary.json').read_text(encoding='utf-8'))
    if result['status'] != 'PASS':
        raise ValueError(f'Incomplete/failed run cannot establish a baseline: {path}')
    metrics = {}
    for source in (path / 'benchmark').rglob('*benchmarkData.json'):
        data = json.loads(source.read_text(encoding='utf-8'))
        for case in data['benchmarks']:
            for name, values in case.get('metrics', {}).items():
                if name.endswith('Ms'):
                    metrics[f"{case['name']}/{name}"] = distribution(values['runs'], minimum=30)
            for name, values in case.get('sampledMetrics', {}).items():
                rows = values['runs']
                pooled = [n for row in rows for n in row]
                value = distribution(pooled, minimum=1)
                value['iterations'] = len(rows)
                value['eligible'] = len(rows) >= 10 and all(rows)
                if name == 'frameOverrunMs':
                    value['over_deadline_percent'] = sum(n > 0 for n in pooled) / len(pooled) * 100
                metrics[f"{case['name']}/{name}"] = value
    ui = {}
    for source in (path / 'journeys').rglob('latency.ndjson'):
        for line in source.read_text(encoding='utf-8').splitlines():
            row = json.loads(line); ui.setdefault(row['metric'], []).append(row['ms'])
    for name, values in ui.items():
        metrics['ui/' + name] = distribution(values, minimum=30)
    required = required_metrics(meta['suite'], meta['api'], meta.get('method'))
    if set(metrics) != required:
        raise ValueError(f'Incomplete measurements: missing={sorted(required - metrics.keys())}, '
                         f'unexpected={sorted(metrics.keys() - required)}: {path}')
    return meta, metrics


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--baseline', type=Path, required=True)
    p.add_argument('--candidate', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    before_meta, before = read_run(args.baseline)
    after_meta, after = read_run(args.candidate)
    if before_meta.get('fresh_install', False) != after_meta.get('fresh_install', False):
        raise ValueError('Mismatching environment: fresh_install')
    for field in ('api', 'abi', 'fingerprint', 'display', 'density', 'font_scale', 'graphics', 'guest_mem_total', 'cpu_online', 'page_size', 'emulator_serialno', 'variant', 'theme', 'suite', 'test_class', 'method', 'argument', 'test_apk_sha256', 'fixture_sha256'):
        if before_meta[field] != after_meta[field]:
            raise ValueError(f'Mismatching environment: {field}')
    rows = []
    for name in sorted(before.keys() | after.keys()):
        a, b = before.get(name), after.get(name)
        status = 'NOT_COMPARABLE' if not a or not b or not a['eligible'] or not b['eligible'] else (
            'RECHECK' if regression(name, a, b) else 'NO_REGRESSION_DETECTED')
        rows.append(dict(name=name, status=status, baseline=a, candidate=b))
    value = dict(measurement='Emulator diagnostics, not physical-device acceptance. UI latency includes automation overhead. '
        'Frame samples are pooled; latency P50/P95 requires at least 30 samples. RECHECK requires a repeated matched pair.',
        baseline=str(args.baseline), candidate=str(args.candidate),
        baseline_apk=before_meta['apk_sha256'], candidate_apk=after_meta['apk_sha256'], metrics=rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f'{len(rows)} metrics; {sum(row["status"] == "RECHECK" for row in rows)} require recheck: {args.output}')


if __name__ == '__main__':
    main()
