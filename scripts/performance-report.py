#!/usr/bin/env python3
"""Preserve completed instrumentation evidence and derive reproducible diagnostic metrics."""
import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
p = argparse.ArgumentParser()
p.add_argument('runs', nargs='+', type=Path)
p.add_argument('--output', type=Path, default=Path('docs/releases/0.1.0-evidence'))
args = p.parse_args()
args.output.mkdir(parents=True, exist_ok=True)


def percentiles(values):
    values = sorted(values)
    assert values
    def at(q):
        x = (len(values) - 1) * q
        i = int(x)
        return round(values[i] + (values[min(i + 1, len(values) - 1)] - values[i]) * (x - i), 3)
    return dict(samples=len(values), minimum=values[0], maximum=values[-1], p50=at(.50), p95=at(.95), p99=at(.99))


def memory(path):
    raw = path.read_text(encoding='utf-8')
    heaps = {}
    for label in ('Native', 'Dalvik'):
        match = re.search(r'^\s*' + label + r' Heap[ \t]+([\d \t]+)$', raw, re.M)
        assert match, f'Heap Alloc 누락: {path}'
        values = [int(x) for x in match.group(1).split()]
        heaps[label.lower() + '_heap_alloc_kib'] = values[-2]
    heaps['combined_heap_alloc_mib'] = round(sum(heaps.values()) / 1024, 3)
    pss = re.search(r'TOTAL PSS:\s*(\d+)', raw)
    if pss:
        heaps['total_pss_mib'] = round(int(pss.group(1)) / 1024, 3)
    heaps['pid'] = re.search(r'MEMINFO in pid (\d+)', raw).group(1)
    return heaps


runs = []
for run in args.runs:
    result = json.loads((run / 'summary.json').read_text(encoding='utf-8'))
    assert result['status'] == 'PASS', f'완료된 통과 실행만 수집합니다: {run}'
    meta = json.loads((run / 'manifest.json').read_text(encoding='utf-8'))
    target = args.output / run.name
    target.mkdir(parents=True, exist_ok=True)
    for name in ('summary.json', 'manifest.json', 'instrumentation.txt'):
        (target / name).write_text((run / name).read_text(encoding='utf-8').replace(str(ROOT), '<workspace>'), encoding='utf-8')
    for note in (run / 'device/captures').rglob('*.txt'):
        shutil.copy2(note, target / note.name)
    entry = dict(run=run.name, suite=meta['suite'], variant=meta['variant'], api=meta['api'],
                 apk_sha256=meta['apk_sha256'], result=result)
    if meta['suite'] == 'macro':
        raw_file = next((run / 'benchmark').rglob('*benchmarkData.json'))
        shutil.copy2(raw_file, target / 'benchmarkData.json')
        data = json.loads(raw_file.read_text(encoding='utf-8'))
        benchmarks = []
        for benchmark in data['benchmarks']:
            row = dict(name=benchmark['name'], iterations=benchmark['repeatIterations'])
            metrics = benchmark.get('metrics', {})
            if 'OpenTextSumMs' in metrics:
                assert all(n == 1 for n in metrics['OpenTextCount']['runs']), '한 번의 열기 구간만 측정해야 합니다.'
                row['open_text_ms'] = percentiles(metrics['OpenTextSumMs']['runs'])
            for name, metric in benchmark.get('sampledMetrics', {}).items():
                samples = [value for iteration in metric['runs'] for value in iteration]
                row[name] = percentiles(samples)
                if name == 'frameOverrunMs':
                    row[name]['over_deadline_percent'] = round(sum(v > 0 for v in samples) / len(samples) * 100, 3)
                    row['reference_frame_goal_met'] = (row[name]['p95'] <= 0 and row[name]['p99'] <= 8
                                                       and row[name]['over_deadline_percent'] <= 5)
            benchmarks.append(row)
        entry['benchmarks'] = benchmarks
        traces = []
        for trace in sorted((run / 'benchmark').rglob('*.perfetto-trace')):
            traces.append(dict(name=trace.name, bytes=trace.stat().st_size,
                               sha256=hashlib.sha256(trace.read_bytes()).hexdigest()))
        (target / 'local-traces.json').write_text(json.dumps(traces, indent=2), encoding='utf-8')
        entry['local_trace_count'] = len(traces)
    if meta['suite'] in ('load', 'repeat', 'soak'):
        folder = next((run / 'device/performance').iterdir())
        raw_target = target / 'performance'
        raw_target.mkdir(exist_ok=True)
        for f in sorted(folder.iterdir()):
            if f.suffix in ('.txt', '.tsv'):
                shutil.copy2(f, raw_target / f.name)
        entry['memory'] = {f.stem.removesuffix('-memory'): memory(f) for f in sorted(folder.glob('*-memory.txt'))}
        if meta['suite'] == 'load':
            entry['open_ready_ms_single_sample'] = {name: int(ms) for name, ms in (line.split('\t') for line in (folder / 'open-times.tsv').read_text(encoding='utf-8').splitlines())}
            entry['heap_delta_50mib_mib'] = round(entry['memory']['050MiB']['combined_heap_alloc_mib'] - entry['memory']['library-baseline']['combined_heap_alloc_mib'], 3)
        if meta['suite'] == 'repeat':
            entry['open_ready_ms'] = percentiles([int(line.split('\t')[1]) for line in (folder / 'reopen.tsv').read_text(encoding='utf-8').splitlines()])
        if meta['suite'] == 'soak':
            entry['soak'] = {key: int(value) for key, value in (line.split('\t') for line in (folder / 'soak.tsv').read_text(encoding='utf-8').splitlines())}
    runs.append(entry)

(args.output / 'results.json').write_text(json.dumps(dict(
    measurement='Emulator diagnostics; not physical-device acceptance. P50/P95/P99 use linear interpolation over all recorded samples. Frame samples are pooled across iterations; Heap Alloc is separate from PSS.',
    runs=runs), ensure_ascii=False, indent=2), encoding='utf-8')
print(f'{len(runs)} verified runs: {args.output}/results.json')
