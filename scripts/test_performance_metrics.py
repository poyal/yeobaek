import unittest
import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from performance_metrics import distribution, regression

spec = importlib.util.spec_from_file_location('compare_performance', Path(__file__).with_name('compare-performance.py'))
compare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compare)


class PerformanceMetricsTest(unittest.TestCase):
    def test_small_sample_is_not_published_as_percentiles(self):
        row = distribution([2, 1, 3])
        self.assertFalse(row['eligible']); self.assertNotIn('p95', row)
        self.assertEqual([1, 2, 3], row['individual_samples'])

    def test_interpolation_and_invalid_measurements(self):
        self.assertAlmostEqual(28.5, distribution(list(range(31)))['p95'])
        for values in ([], [float('nan')], [float('inf')]):
            with self.assertRaises(ValueError): distribution(values)

    def test_latency_requires_both_relative_and_absolute_regression(self):
        def row(n): return distribution([n] * 30)
        self.assertFalse(regression('open', row(100), row(130)))
        self.assertFalse(regression('open', row(1000), row(1100)))
        self.assertTrue(regression('open', row(1000), row(1200)))

    def test_frame_deadline_percentage_is_a_separate_trigger(self):
        a = dict(distribution([0] * 30), over_deadline_percent=2)
        b = dict(distribution([1] * 30), over_deadline_percent=4)
        self.assertTrue(regression('frameOverrunMs', a, b))
        self.assertTrue(regression('frameDurationCpuMs', distribution([1] * 30), distribution([3] * 30)))

    def report(self, root, method=None, api=36, case=None):
        (root / 'manifest.json').write_text(json.dumps(dict(suite='formats-macro', api=str(api), method=method)))
        (root / 'summary.json').write_text(json.dumps(dict(status='PASS')))
        (root / 'benchmark').mkdir(exist_ok=True)
        (root / 'benchmark/result-benchmarkData.json').write_text(json.dumps(dict(benchmarks=[case or dict(
            name='coldProcessStartup', metrics=dict(timeToInitialDisplayMs=dict(runs=[200] * 30)))])))

    def test_pass_summary_cannot_hide_missing_suite_measurements(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.report(root)
            with self.assertRaisesRegex(ValueError, 'Incomplete measurements'):
                compare.read_run(root)

    def test_selected_method_requires_its_measurements_and_respects_api(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.report(root, method='coldProcessStartup')
            self.assertEqual({'coldProcessStartup/timeToInitialDisplayMs'}, set(compare.read_run(root)[1]))
            self.report(root, method='scrollPdf')
            with self.assertRaisesRegex(ValueError, 'Incomplete measurements'):
                compare.read_run(root)
            for api in (30, 36):
                with self.subTest(api=api):
                    frame = dict(name='scrollPdf', sampledMetrics=dict(frameDurationCpuMs=dict(runs=[[10, 11]] * 10)))
                    self.report(root, method='scrollPdf', api=api, case=frame)
                    if api == 30:
                        self.assertEqual({'scrollPdf/frameDurationCpuMs'}, set(compare.read_run(root)[1]))
                    else:
                        with self.assertRaisesRegex(ValueError, 'frameOverrunMs'):
                            compare.read_run(root)
                        frame['sampledMetrics']['frameOverrunMs'] = dict(runs=[[-1, 2]] * 10)
                        self.report(root, method='scrollPdf', api=api, case=frame)
                        self.assertEqual(2, len(compare.read_run(root)[1]))


if __name__ == '__main__': unittest.main()
