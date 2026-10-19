#!/usr/bin/env python3
"""Actual UI journey and annotated-report inputs. Only dedicated yeobaek AVD/debug data."""
import argparse, base64, datetime, hashlib, json, os, pathlib, re, subprocess, time, xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5588')
parser.add_argument('--resume', type=int, default=1)
parser.add_argument('--stage', choices=['prepare', 'capture', 'restore-fresh', 'external', 'report'], default='capture')
args = parser.parse_args()
ADB = ROOT / '.tools/android-sdk/platform-tools/adb.exe'
OUT = ROOT / '.tools/verification/reader-upgrade'
OUT.mkdir(parents=True, exist_ok=True)
PKG = 'com.poyal.yeobaek.debug'

def adb(*parts, timeout=35):
    return subprocess.run([str(ADB), '-s', args.serial, *map(str, parts)], check=True, capture_output=True, timeout=timeout).stdout

def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/reader-upgrade.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/reader-upgrade.xml')).iter('node'))

def bounds(n): return list(map(int, re.findall(r'\d+', n.get('bounds', ''))))

def find(tag=None, text=None, timeout=10):
    if tag == 'backup_restore_confirm' and text is None: text = '없는 기록 추가'
    until = time.monotonic() + timeout
    while True:
        for n in nodes():
            if (tag is not None and n.get('resource-id') == tag) or (text is not None and n.get('text') == text): return n
        if time.monotonic() >= until: return None
        time.sleep(.2)

def tap_node(n):
    assert n is not None, 'Missing UI node'
    x1, y1, x2, y2 = bounds(n)
    assert y2 > y1 and x2 > x1, n.attrib
    adb('shell', 'input', 'tap', (x1+x2)//2, (y1+y2)//2); time.sleep(.4)

def click(tag):
    n = find(tag=tag); assert n is not None, tag; tap_node(n)

def scroll_to(tag, container='library_list'):
    for _ in range(14):
        ns = nodes()
        found = next((n for n in ns if n.get('resource-id') == tag and bounds(n)[3]-bounds(n)[1] > 20), None)
        if found is not None: return found
        parent = next((n for n in ns if n.get('resource-id') == container), None)
        assert parent is not None, container
        x1, y1, x2, y2 = bounds(parent)
        adb('shell', 'input', 'swipe', (x1+x2)//2, y2-50, (x1+x2)//2, y1+70, 400); time.sleep(.3)
    raise AssertionError(tag)

def text_input(n, value):
    tap_node(n); adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    adb('shell', 'input', 'keycombination', '113', '29')
    adb('shell', 'input', 'text', value)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); time.sleep(.3)

captures = json.loads((OUT/'captures.json').read_text(encoding='utf-8')) if (args.resume > 1 or args.stage != 'capture') and (OUT/'captures.json').exists() else []

def record_capture(record):
    captures[:] = sorted([item for item in captures if item['name'] != record['name']] + [record], key=lambda item: item['name'])
    (OUT / 'captures.json').write_text(json.dumps(captures, ensure_ascii=False, indent=2), encoding='utf-8')
def shot(name, title, detail, tags):
    time.sleep(.7); ns = nodes()
    png = adb('exec-out', 'screencap', '-p')
    (OUT / f'{name}.png').write_bytes(png)
    (OUT / f'{name}.xml').write_bytes(adb('shell', 'cat', '/sdcard/reader-upgrade.xml'))
    boxes = []
    for number, (tag, label) in enumerate(tags, 1):
        found = next((n for n in ns if n.get('resource-id') == tag), None)
        if found is None and tag == 'backup_restore_confirm': found = next((n for n in ns if n.get('text') == '없는 기록 추가'), None)
        if found is None and tag == 'image_body': found = next((n for n in ns if n.get('content-desc','').startswith('이미지 ') and '페이지' in n.get('content-desc','')), None)
        assert found is not None, f'{name}: missing annotation {tag}'
        boxes.append(dict(number=number, label=label, bounds=bounds(found)))
    record_capture(dict(name=name, title=title, detail=detail, boxes=boxes))
    print('Captured', name, flush=True)

def prepare():
    from PIL import Image, ImageDraw, ImageFont
    import zipfile, io, shutil
    fixtures = ROOT / '.tools/fixtures/ReaderReview'; fixtures.mkdir(exist_ok=True)
    shutil.copyfile(ROOT / '.tools/fixtures/Yeobaek/비가 그친 오후.txt', fixtures / '01 Novel.txt')
    shutil.copyfile(ROOT / 'app/src/androidTest/assets/pdf/mixed.pdf', fixtures / '02 Guide.pdf')
    font = ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf', 54)
    with zipfile.ZipFile(fixtures / '03 Comic.cbz', 'w', zipfile.ZIP_DEFLATED) as archive:
        for page, dimensions in enumerate([(1400, 900), (700, 1000)], 1):
            image = Image.new('RGB', dimensions, 'white'); draw = ImageDraw.Draw(image)
            w,h=dimensions
            draw.rectangle((60,60,w//2-18,h-60), fill='#dce9ed', outline='#263842', width=5)
            draw.rectangle((w//2+18,60,w-60,h-60), fill='#e7e1ce', outline='#263842', width=5)
            draw.text((85,95), 'LEFT', font=font, fill='#263842'); draw.text((w//2+45,95), 'RIGHT', font=font, fill='#263842')
            for x in [90,w//2+45]:
                draw.ellipse((x+30,260,x+200,430), fill='#99b8c6', outline='#263842', width=4)
                draw.rounded_rectangle((x,480,x+220,700), radius=20, fill='white', outline='#263842', width=4)
            data=io.BytesIO();image.save(data,format='PNG');archive.writestr(f'{page:02}.png',data.getvalue())
    adb('shell','mkdir','-p','/sdcard/Download/ReaderReview')
    for file in fixtures.iterdir(): adb('push',str(file),'/sdcard/Download/ReaderReview/',timeout=60)

def connect():
    click('connect_folder')
    roots = next((n for n in nodes() if n.get('content-desc') in ('Show roots','표시 위치')), None)
    if roots is not None: tap_node(roots)
    downloads=find(text='Downloads',timeout=3) or find(text='다운로드',timeout=2)
    if downloads is not None: tap_node(downloads)
    download=find(text='Download',timeout=2)
    if download is not None: tap_node(download)
    folder=find(text='ReaderReview'); assert folder is not None; tap_node(folder)
    select=next((n for n in nodes() if n.get('resource-id','').endswith(':id/action_menu_select')),None)
    tap_node(select or find(tag='android:id/button1'))
    allow=find(tag='android:id/button1',timeout=4)
    if allow is not None: tap_node(allow)
    assert find(tag='folder_refresh') is not None

def capture():
    if args.resume <= 1:
        prepare()
        adb('shell','am','force-stop',PKG); adb('shell','pm','clear',PKG)
        adb('shell','am','start','-W','-n',f'{PKG}/com.poyal.yeobaek.MainActivity')
        connect(); click('folder_favorite')
        click('file_01 Novel.txt'); assert find(tag='reader_footer',timeout=60) is not None
        time.sleep(1); adb('shell','input','swipe','360','1100','360','450','400'); time.sleep(1)
        click('reader_bookmark'); click('reader_txt'); click('scope_BOOK'); click('font_larger')
        shot('01-scoped-settings','책마다 필요한 항목만 설정','공통값은 유지하면서 이 책의 글자 크기만 19로 변경했습니다.', [('scope_BOOK','이 책에 적용'),('inherit_size','크기만 개별 저장'),('quick_font','접힌 글꼴 선택')])
    if args.resume <= 2:
        tap_node(scroll_to('reader_awake','quick_options')); click('brightness_system')
        scroll_to('reader_awake','quick_options')
        shot('02-screen-controls','독서 화면 조절','밝기·회전·화면 유지 설정은 모든 형식의 독서 화면에 적용합니다.', [('reader_brightness','독서 화면 밝기'),('rotation_PORTRAIT','회전 잠금'),('reader_awake','화면 켜두기')])
        click('sheet_close'); click('reader_back')
    if args.resume <= 3:
        tap_node(scroll_to('folder_search')); text_input(find(tag='folder_search'), 'Novel')
        scroll_to('status_01 Novel.txt')
        shot('03-folder-search','폴더에서 찾고 이어 읽기','현재 폴더의 파일명 검색, 정렬, 즐겨찾기와 읽기 상태를 추가했습니다.', [('folder_search','현재 폴더 검색'),('sort_NAME','정렬 방식'),('status_01 Novel.txt','독서 진행 상태')])
        clear=next(n for n in nodes() if n.get('content-desc')=='검색 지우기');tap_node(clear)
    if args.resume <= 4:
        tap_node(scroll_to('file_02 Guide.pdf')); assert find(tag='pdf_footer',timeout=60) is not None
        click('pdf_settings')
        shot('04-pdf-quick','PDF 빠른 읽기 설정','PDF도 같은 설정 범위와 빠른 조절 동선을 사용합니다.', [('scope_COMMON','공통 기본값'),('pdf_fit_WIDTH','화면 맞춤'),('pdf_inversion_switch','색상 반전')])
        click('sheet_close'); click('pdf_back')
    if args.resume <= 5:
        tap_node(scroll_to('file_03 Comic.cbz')); assert find(tag='image_footer',timeout=60) is not None
        click('image_settings'); click('image_mode_PAGED'); click('sheet_close');time.sleep(2)
        shot('05-comic-whole','펼침면 원본 보기','가로로 긴 원본 한 페이지입니다. 원본 파일과 페이지 번호를 유지합니다.', [('image_body','원본 펼침면')])
    if args.resume <= 6:
        click('image_settings'); click('image_split'); click('sheet_close');time.sleep(1)
        shot('06-comic-left','펼침면을 반쪽씩','세로 휴대폰에서 왼쪽을 먼저 확대해서 읽습니다.', [('image_page','원본 번호 + 왼쪽'),('image_body','반쪽을 화면에 맞춤')])
    if args.resume <= 7:
        adb('shell','input','swipe','610','800','110','800','350');time.sleep(1)
        assert '오른쪽' in find(tag='image_page').get('text','')
        shot('07-comic-right','다음 반쪽과 이어읽기','한 번 넘기면 같은 페이지의 오른쪽입니다. 재실행 시 어느 쪽인지 복원합니다.', [('image_page','같은 페이지 · 오른쪽')])
    if args.resume <= 8:
        click('image_settings');tap_node(scroll_to('image_crop_edit','quick_options'))
        slider=find(tag='crop_left');x1,y1,x2,y2=bounds(slider);adb('shell','input','tap',x1+(x2-x1)//3,(y1+y2)//2);time.sleep(.4)
        shot('08-crop-preview','미리보며 여백 자르기','테두리를 보면서 조절하고 책 전체 또는 현재 페이지에 적용합니다.', [('crop_left','왼쪽 자르기 조절'),('crop_options','미리보기와 원본 비율')])
        tap_node(scroll_to('crop_single','crop_options'));click('crop_apply');click('image_back')
    if args.resume <= 9:
        click('nav_settings');click('settings_general');scroll_to('backup_import','general_settings_options')
        shot('09-backup','독서 기록 수동 백업','책 원본·캐시·진단 로그를 제외하고 기록과 설정을 내보냅니다.', [('backup_export','백업 파일 저장'),('backup_import','기록 가져오기')])
        click('backup_export')
        edit=next(n for n in nodes() if n.get('class')=='android.widget.EditText')
        text_input(edit,'reader-review-backup.json')
        save=next((n for n in nodes() if n.get('resource-id','').endswith(':id/action_menu_save')),None)
        tap_node(save or find(tag='android:id/button1'))
        overwrite=find(tag='android:id/button1',timeout=2)
        if overwrite is not None:tap_node(overwrite)
        assert find(tag='backup_status',timeout=10) is not None
        click('backup_import');tap_node(find(text='reader-review-backup.json',timeout=15))
    if args.resume <= 10:
        assert find(tag='backup_restore_confirm',timeout=15) is not None
        shot('10-restore-preview','복원 전에 내용 확인','책·읽기 위치·책갈피 수와 중복 책을 확인합니다. 기존 기록은 유지합니다.', [('backup_restore_confirm','없는 기록만 추가')])
        click('backup_restore_confirm');time.sleep(1)
        assert '복원했습니다' in find(tag='backup_status').get('text','')
    manifest=dict(date=datetime.datetime.now().astimezone().isoformat(),serial=args.serial,
        api=adb('shell','getprop','ro.build.version.sdk').decode().strip(),apk_sha256=adb('shell','sha256sum',adb('shell','pm','path',PKG).decode().strip().removeprefix('package:')).decode().split()[0],
        checks=['folder search and sort UI','book-specific size override','screen controls','PDF quick settings','comic split left/right','page crop','backup export and duplicate-safe restore'])
    (OUT/'journey.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')

def external_capture():
    # ExternalOpenTest produces these screenshots while the granted image is open.
    source = '/sdcard/Android/data/com.poyal.yeobaek.e2e/files/reader-upgrade'
    name = '13-external-image'
    for extension in ('png', 'xml'):
        adb('pull', f'{source}/external-image.{extension}', str(OUT / f'{name}.{extension}'))
    node = next(n for n in ET.parse(OUT / f'{name}.xml').iter('node') if n.get('resource-id') == 'image_page')
    assert node.get('text') == '1 / 1 페이지'
    record_capture(dict(name=name, title='다른 앱에서 바로 열기',
        detail='별도 테스트 앱이 전달한 임시 읽기 권한으로 TXT와 이미지 열기를 검증했습니다. 이미지는 폴더 접근 없이 한 장만 엽니다.',
        boxes=[dict(number=1, label='외부 이미지 한 장을 독립 문서로 표시', bounds=bounds(node))]))


def report():
    import html
    records=json.loads((OUT/'captures.json').read_text(encoding='utf-8'))
    verification=json.loads((OUT/'verification.json').read_text(encoding='utf-8'))
    cards=[]; panels=[]
    for record in records:
        name=record['name']; encoded=base64.b64encode((OUT/f'{name}.png').read_bytes()).decode()
        overlay=[]; legend=[]
        for box in record['boxes']:
            x,y,x2,y2=box['bounds']; number=box['number']
            overlay.append(f'<rect x="{x+2}" y="{y+2}" width="{max(0,x2-x-4)}" height="{max(0,y2-y-4)}" rx="8"/><circle cx="{x+20}" cy="{y+20}" r="19"/><text x="{x+20}" y="{y+27}">{number}</text>')
            legend.append(f'<li><b>{number}</b>{html.escape(box["label"])}</li>')
        picture=f'<button class="capture" aria-label="{html.escape(record["title"])} 크게 보기"><img loading="lazy" width="720" height="1600" src="data:image/png;base64,{encoded}" alt="{html.escape(record["title"])}"><svg class="marks" viewBox="0 0 720 1600" aria-hidden="true">{"".join(overlay)}</svg></button>'
        if name[:2] in ('05','06','07'):
            panels.append(f'<div>{picture}<p>{html.escape(record["title"])}</p></div>')
            if name[:2]=='07': cards.append('<article class="spread" id="05-comic-whole"><div class="explanation"><span class="eyebrow">실제 에뮬레이터 화면 · 05–07</span><h2>펼침면을 휴대폰에 맞게</h2><p>왼쪽은 원본 한 페이지, 가운데는 왼쪽 반쪽, 오른쪽은 한 번 넘긴 다음 반쪽입니다. 원본 페이지 번호는 1 / 2로 유지됩니다. 검증용 이미지로 촬영했습니다.</p></div><div class="triptych">'+''.join(panels)+'</div></article>')
        else:
            cards.append(f'''<article id="{name}"><div class="explanation"><span class="eyebrow">실제 에뮬레이터 화면 · {name[:2]}</span><h2>{html.escape(record['title'])}</h2><p>{html.escape(record['detail'])}</p><ol>{''.join(legend)}</ol><small>화면을 누르면 크게 볼 수 있습니다.</small></div>{picture}</article>''')
    results=''.join(f'<tr><th>{html.escape(row[0])}</th><td>{html.escape(row[1])}</td></tr>' for row in verification['results'])
    notes=''.join(f'<li>{html.escape(note)}</li>' for note in verification['notes'])
    output='''<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>여백 · 독서 기능 변경 보고서</title><style>
    :root{--paper:#f8f6ef;--ink:#28342f;--muted:#637066;--green:#52664b;--line:#d8dece;--mark:#c54b2a}*{box-sizing:border-box}html{scroll-behavior:auto}body{margin:0;background:var(--paper);color:var(--ink);font:16px/1.8 'Segoe UI','Malgun Gothic',sans-serif}main{max-width:1080px;margin:auto;padding:48px 28px}header{padding:36px 0 42px;border-bottom:1px solid var(--line)}h1{font-size:clamp(32px,5vw,54px);font-weight:600;letter-spacing:-2px;line-height:1.25;margin:18px 0}h2{font-size:29px;line-height:1.4;letter-spacing:-1px;margin:12px 0}p{max-width:700px}.eyebrow{font-size:12px;letter-spacing:2px;color:var(--green);font-weight:700}.lead{font-size:19px;color:var(--muted)}.chips{display:flex;gap:8px;flex-wrap:wrap}.chip{border:1px solid var(--line);padding:4px 13px;border-radius:100px;font-size:13px}nav{position:sticky;top:0;z-index:4;display:flex;align-items:center;gap:18px;background:#f8f6eff5;backdrop-filter:blur(8px);padding:14px 0;border-bottom:1px solid var(--line);font-size:14px}a{color:var(--green);text-underline-offset:4px}nav label{margin-left:auto;white-space:nowrap;cursor:pointer}table{border-collapse:collapse;width:100%;margin:24px 0 36px}th,td{text-align:left;padding:14px 12px;border-bottom:1px solid var(--line);vertical-align:top}th{font-weight:600}thead{background:#edf0e5}article{display:grid;grid-template-columns:1fr 320px;gap:64px;align-items:start;padding:64px 0;border-bottom:1px solid var(--line)}.spread{display:block}.triptych{display:grid;grid-template-columns:repeat(3,1fr);gap:18px;align-items:start}.triptych p{font-size:13px;text-align:center}.explanation{padding-top:24px}.capture{position:relative;display:block;border:1px solid var(--line);padding:0;line-height:0;background:white;box-shadow:0 18px 50px #28342f14;border-radius:18px;overflow:hidden;cursor:zoom-in;width:100%}.capture img{width:100%;height:auto;display:block}.marks{position:absolute;inset:0;width:100%;height:100%;pointer-events:none}.marks rect{fill:#c54b2a08;stroke:var(--mark);stroke-width:4}.marks circle{fill:var(--mark)}.marks text{fill:white;text-anchor:middle;font:700 24px 'Segoe UI',sans-serif}.hide-marks .marks{display:none}ol{padding:0;list-style:none;margin:24px 0}ol li{margin:12px 0;display:flex;align-items:center;gap:12px}ol b{display:inline-flex;justify-content:center;align-items:center;background:var(--mark);color:white;border-radius:100%;width:25px;height:25px;font-size:13px;flex-shrink:0}small{color:var(--muted);font-size:12px}section{padding-top:40px}.notice{padding:20px 24px;background:#edf0e5;border-left:3px solid var(--green);margin:28px 0}.notice p{margin:0}.results th{width:28%}footer{color:var(--muted);font-size:13px;padding:28px 0}dialog{border:0;padding:20px;background:var(--paper);max-width:min(90vw,770px);max-height:94vh;border-radius:16px}dialog::backdrop{background:#19251fbb}dialog .capture{width:min(720px,80vw);cursor:default}dialog button.close{position:sticky;top:0;float:right;z-index:5;background:var(--green);color:white;border:0;border-radius:20px;padding:10px 18px;cursor:pointer;font-size:16px}.badge{font-size:13px;color:var(--green);font-weight:700}@media(max-width:700px){main{padding:20px}article{grid-template-columns:1fr;gap:24px;padding:36px 0}.capture{max-width:360px;margin:auto}.explanation{padding-top:0}nav{gap:12px;font-size:12px}h2{font-size:25px}th,td{padding:10px 6px;font-size:14px}}@media print{nav,dialog{display:none}article{break-inside:avoid;grid-template-columns:1fr 220px;padding:24px 0;gap:24px}.capture{box-shadow:none}h1{font-size:32px}}
    </style></head><body><main><header><span class="eyebrow">YEOBAEK · READER UPDATE</span><h1>읽는 자리를<br>더 편하게.</h1><p class="lead">TXT·PDF·만화 뷰어의 설정, 탐색, 위치 보존과 백업을 강화했습니다. 아래 화면은 구현한 디버그 앱을 에뮬레이터에서 직접 조작하며 캡처한 결과입니다.</p><div class="chips"><span class="chip">Android 16 · API 36</span><span class="chip">720 × 1600 · 휴대폰</span><span class="chip">원본 PNG + HTML 표시</span><span class="chip">공개 배포 전 개발 빌드</span></div></header><nav><a href="#changes">변경 내용</a><a href="#screens">실제 화면</a><a href="#tests">검증 결과</a><label><input id="annotations" type="checkbox" checked> 변경 부분 표시</label></nav>
    <section id="changes"><span class="eyebrow">WHAT CHANGED</span><h2>바꾼 부분</h2><table><thead><tr><th>영역</th><th>이전</th><th>변경 후</th></tr></thead><tbody><tr><th>읽기 설정</th><td>형식별 공통값 · 전체 설정 화면</td><td>빠른 설정 + 책·폴더별 항목 단위 예외</td></tr><tr><th>독서 화면</th><td>시스템 밝기·회전·화면 꺼짐</td><td>독서 화면 전용 밝기·회전·화면 유지</td></tr><tr><th>만화</th><td>원본 페이지·두 쪽 보기</td><td>펼침면 반쪽 보기, 수동·자동 여백 자르기</td></tr><tr><th>폴더</th><td>자연 이름순 탐색</td><td>이름 검색·세 가지 정렬·즐겨찾기·진행 표시·제한된 미리보기</td></tr><tr><th>기록</th><td>앱 내부 저장</td><td>수동 백업·미리보기·기존 기록 보존 병합</td></tr><tr><th>파일 열기</th><td>앱 안에서 폴더 선택</td><td>다른 앱의 content URI로 TXT·PDF·이미지·압축파일 열기</td></tr></tbody></table><div class="notice"><p>TXT 자동 목차, 신규 형광펜·메모, TTS·자동 스크롤·리모컨은 추가하지 않았습니다. EPUB는 별도의 EPUB 검사 모음에서 검증하며 이 보고서의 범위에는 포함하지 않습니다.</p></div></section><section id="screens"><span class="eyebrow">ON THE EMULATOR</span><h2>변경 화면과 표시</h2>'''+''.join(cards)+'''</section><section id="tests"><span class="eyebrow">VERIFICATION</span><h2>실제로 확인한 결과</h2><table class="results">'''+results+'''</table><ul>'''+notes+'''</ul><p><a href="../../../app/build/outputs/apk/debug/app-debug.apk">검증용 디버그 APK</a> · <a href="verification.json">검증 메타데이터</a> · <a href="captures.json">캡처 표시 좌표</a></p></section><footer>'''+html.escape(verification['date'])+''' · 화면 이미지는 앱 실행 결과이며, 강조 테두리와 번호만 HTML 위에 표시했습니다. 파일 하나로 화면을 볼 수 있도록 이미지를 포함했습니다.</footer></main><dialog id="zoom"><button class="close">닫기 ×</button><div id="zoom-body"></div></dialog><script>
    const zoom=document.querySelector('#zoom');document.querySelectorAll('article .capture').forEach(el=>el.addEventListener('click',()=>{const copy=el.cloneNode(true);copy.removeAttribute('aria-label');document.querySelector('#zoom-body').replaceChildren(copy);zoom.showModal()}));document.querySelector('#annotations').addEventListener('change',e=>document.body.classList.toggle('hide-marks',!e.target.checked));document.querySelector('.close').addEventListener('click',()=>zoom.close());zoom.addEventListener('click',e=>{if(e.target===zoom)zoom.close()});
    </script></body></html>'''
    (OUT/'index.html').write_text(output,encoding='utf-8')
    print(f'Wrote {OUT / "index.html"}',flush=True)

def restore_fresh():
    adb('shell','am','force-stop',PKG); adb('shell','pm','clear',PKG)
    adb('shell','am','start','-W','-n',f'{PKG}/com.poyal.yeobaek.MainActivity')
    click('nav_settings');click('settings_general');scroll_to('backup_import','general_settings_options');click('backup_import')
    backup=find(text='reader-review-backup.json',timeout=5)
    if backup is None:
        roots=next((n for n in nodes() if n.get('content-desc') in ('Show roots','표시 위치')),None)
        if roots is not None:tap_node(roots)
        downloads=find(text='Downloads',timeout=3)
        if downloads is not None:tap_node(downloads)
        download=find(text='Download',timeout=2)
        if download is not None:tap_node(download)
        folder=find(text='ReaderReview',timeout=3)
        if folder is not None:tap_node(folder)
        backup=find(text='reader-review-backup.json')
    tap_node(backup)
    assert find(tag='backup_restore_confirm') is not None
    assert any('겹치는 기록 0권' in n.get('text','') for n in nodes())
    shot('11-fresh-restore','빈 앱에 기록 복원','테스트 앱 데이터를 초기화한 뒤 같은 백업을 가져왔습니다. 기존 기록 0권에서 복원합니다.', [('backup_restore_confirm','새 환경에 기록 복원')])
    click('backup_restore_confirm');time.sleep(.5)
    click('page_back');click('nav_recent');tap_node(scroll_to('recent_01 Novel.txt','recent_list'))
    assert find(tag='find_missing_book') is not None
    shot('12-reconnect-source','원본 파일은 다시 연결','기록은 복원되지만 OS 파일 접근 권한은 복원되지 않습니다. 원본을 다시 선택해 이어 읽습니다.', [('find_missing_book','기록을 유지하며 파일 재연결')])
    click('find_missing_book');tap_node(find(text='01 Novel.txt'))
    assert find(tag='reader_footer',timeout=60) is not None
    assert find(tag='reader_percent').get('text') != '0.0%'
    click('reader_txt');click('scope_BOOK')
    assert any('글자 크기 19' in n.get('text','') for n in nodes())
    click('sheet_close')
    (OUT/'fresh-restore.json').write_text(json.dumps(dict(passed=True,checks=['empty app restore','permission loss and relink','reading position retained','book size override restored']),indent=2),encoding='utf-8')

if args.stage != 'report':
    assert args.serial.startswith('emulator-')
    assert adb('emu','avd','name').decode().splitlines()[0].startswith('yeobaek-')
if args.stage == 'prepare':prepare()
elif args.stage == 'restore-fresh':restore_fresh()
elif args.stage == 'external':external_capture()
elif args.stage == 'capture':
    try:capture()
    except Exception:
        (OUT/'failure.png').write_bytes(adb('exec-out','screencap','-p'))
        (OUT/'failure.xml').write_bytes(adb('shell','cat','/sdcard/reader-upgrade.xml'))
        raise
else: report()
