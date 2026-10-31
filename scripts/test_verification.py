import unittest
from verification import instrumentation_result, owned_processes, require_test_device


def event(name, code):
    cls, method = name.split('#')
    return f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: {code}\n'


class VerificationTest(unittest.TestCase):
    names = ['example.Tests#first', 'example.Tests#second']

    def output(self):
        return ''.join(event(n, 1) + event(n, 0) for n in self.names) + 'OK (2 tests)\nINSTRUMENTATION_CODE: -1\n'

    def test_complete_execution(self):
        self.assertEqual('PASS', instrumentation_result(self.output(), self.names)['status'])

    def test_crash_after_success_summary_is_failure(self):
        for tail in ('INSTRUMENTATION_RESULT: shortMsg=Process crashed\n', 'INSTRUMENTATION_FAILED: crash\n',
                     'INSTRUMENTATION_ABORTED: host interrupted\n'):
            self.assertEqual('FAIL', instrumentation_result(self.output() + tail, self.names)['status'])

    def test_missing_normal_exit_or_wrong_process_exit(self):
        self.assertEqual('FAIL', instrumentation_result(self.output().replace('INSTRUMENTATION_CODE: -1\n', ''), self.names)['status'])
        self.assertEqual('FAIL', instrumentation_result(self.output(), self.names, 1)['status'])

    def test_duplicates_are_not_hidden_by_a_set(self):
        raw = event(self.names[0], 1) + event(self.names[0], 0) + self.output()
        self.assertEqual([self.names[0]], instrumentation_result(raw, self.names)['duplicate'])

    def test_missing_start_and_unexpected_test_fail(self):
        self.assertEqual('FAIL', instrumentation_result(self.output().replace(event(self.names[0], 1), ''), self.names)['status'])
        self.assertEqual('FAIL', instrumentation_result(self.output(), [self.names[0], 'example.Tests#other'])['status'])

    def test_ignored_assumption_and_failure_are_distinct(self):
        for code, key in ((-3, 'skipped'), (-4, 'skipped'), (-1, 'failed'), (-2, 'failed')):
            raw = self.output().replace(event(self.names[0], 0), event(self.names[0], code))
            result = instrumentation_result(raw, self.names)
            self.assertEqual('FAIL', result['status'])
            self.assertEqual([self.names[0]], result[key])
            self.assertEqual([], result['missing'])

    def test_never_started_and_interrupted_are_separate(self):
        result = instrumentation_result(event(self.names[0], 1), self.names)
        self.assertEqual([self.names[1]], result['missing'])
        self.assertEqual([self.names[0]], result['incomplete'])
        self.assertEqual([], result['failed'])

    def test_manual_avds_and_physical_devices_are_protected(self):
        for serial, avd in [('device-123', 'yeobaek-release-040'), ('emulator-5554', 'yeobaek-api36'), ('emulator-5554', 'yeobaek-ridi-epub-20261025')]:
            with self.assertRaises(ValueError):
                require_test_device(serial, avd)
        require_test_device('emulator-5582', 'yeobaek-release-040', 'yeobaek-release-040')
        with self.assertRaises(ValueError):
            require_test_device('emulator-5582', 'yeobaek-release-040', 'yeobaek-release-api30-040')

    def test_isolated_webview_is_counted_only_for_its_owner(self):
        processes = ('PID NAME\n10 com.poyal.yeobaek\n11 com.poyal.yeobaek:pdf\n'
                     '12 com.android.webview:sandboxed_process0\n13 com.android.webview:sandboxed_process1\n'
                     '14 com.poyal.yeobaek.e2e\n')
        services = '''ACTIVITY MANAGER SERVICES
  * ServiceRecord{abc u0 com.poyal.yeobaek/SandboxedProcessService0}
    packageName=com.poyal.yeobaek
    app=ProcessRecord{def 12:com.android.webview:sandboxed_process0/u0a154i239}
  * ServiceRecord{ghi u0 com.example.other/SandboxedProcessService0}
    packageName=com.example.other
    app=ProcessRecord{jkl 13:com.android.webview:sandboxed_process1/u0a155i240}
'''
        self.assertEqual({'10': 'com.poyal.yeobaek', '11': 'com.poyal.yeobaek:pdf',
                          '12': 'com.android.webview:sandboxed_process0'},
                         owned_processes(processes, services, 'com.poyal.yeobaek'))

    def test_stale_service_record_cannot_select_reused_pid(self):
        services = '''  * ServiceRecord{abc u0 com.poyal.yeobaek/SandboxedProcessService0}
    packageName=com.poyal.yeobaek
    app=ProcessRecord{def 12:com.android.webview:sandboxed_process0/u0a154i239}
'''
        self.assertEqual({}, owned_processes('PID NAME\n12 com.example.other\n', services, 'com.poyal.yeobaek'))


if __name__ == '__main__':
    unittest.main()
