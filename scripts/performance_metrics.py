"""Small pure analysis functions; no device access or I/O on import."""
import math


def required_metrics(suite, api, method=None):
    """Expected observations, including API-dependent frame metrics."""
    frame_names = ['frameDurationCpuMs'] + (['frameOverrunMs'] if int(api) >= 31 else [])
    if suite == 'macro':
        cases = {name: [f'{name}/{metric}' for metric in frame_names]
                 for name in ('scroll10MiB', 'scroll50MiB', 'scrollLongParagraph', 'scrollManyEmptyLines')}
        cases.update({name: [f'{name}/OpenTextSumMs']
                      for name in ('warmOpen10MiB', 'warmOpen10MiBWithoutProfile')})
    elif suite == 'formats-macro':
        cases = {name: [f'{name}/{metric}' for metric in frame_names]
                 for name in ('cachedOpenFourFormats', 'scrollText', 'scrollPdf', 'scrollImages', 'scrollEpub')}
        cases['coldProcessStartup'] = ['coldProcessStartup/timeToInitialDisplayMs']
        cases['cachedOpenFourFormats'] += ['ui/cached_open_' + kind for kind in ('reader', 'pdf', 'image', 'epub')]
        cases['seekAndSearchThreeFormats'] = ['ui/' + action + '_' + kind
                                             for kind in ('reader', 'pdf', 'epub') for action in ('navigate', 'search')]
        cases['imageSeekAndTextLayout'] = ['ui/navigate_image', 'ui/txt_font_change_until_reader_ready']
        cases['listThousandFiles'] = ['ui/list_1000_files']
    else:
        raise ValueError(f'Unsupported benchmark suite: {suite}')
    if method:
        if method not in cases:
            raise ValueError(f'Unknown benchmark method: {suite}/{method}')
        return set(cases[method])
    return {metric for values in cases.values() for metric in values}


def distribution(values, minimum=30):
    if not values or any(not math.isfinite(value) for value in values):
        raise ValueError('Missing or non-finite measurements')
    values = sorted(values)
    result = dict(samples=len(values), minimum=values[0], maximum=values[-1], eligible=len(values) >= minimum)
    if not result['eligible']:
        result['individual_samples'] = values
        return result
    for label, q in [('p50', .5), ('p95', .95), ('p99', .99)]:
        at = (len(values) - 1) * q; lo = int(at)
        result[label] = values[lo] + (values[min(lo + 1, len(values) - 1)] - values[lo]) * (at - lo)
    return result


def regression(name, before, after):
    if not before['eligible'] or not after['eligible']:
        raise ValueError('Insufficient samples')
    if 'frame' in name.lower():
        return after['p95'] - before['p95'] >= 2 or (
            'over_deadline_percent' in before and after['over_deadline_percent'] - before['over_deadline_percent'] >= 2)
    delta = after['p95'] - before['p95']
    return delta >= 50 and after['p95'] >= before['p95'] * 1.2
