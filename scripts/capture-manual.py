#!/usr/bin/env python3
"""Capture the public 0.3.0 APK on the dedicated yeobaek-manual-030 emulator.

UI is always driven through Android; PNGs are unaltered adb screencaps.
Use --stage inspect to see controls, or --stage all for the ordered journey.
"""
import argparse
import datetime
import hashlib
import json
import os
import shlex
import subprocess
import struct
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import uiautomator2 as u2

ROOT = Path(__file__).resolve().parent.parent
PKG = 'com.poyal.yeobaek'
APK_SHA = 'e8b51930dcac76138b765fb370b714cd469a361517cfa69509f4409a226a8c59'
AVD = 'yeobaek-manual-030'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', default='emulator-5590')
parser.add_argument('--adb', type=Path, default=ROOT / '.tools/android-sdk/platform-tools' / ('adb.exe' if os.name == 'nt' else 'adb'))
parser.add_argument('--output', type=Path, default=ROOT / 'docs/screenshots')
parser.add_argument('--stage', default='inspect')
args = parser.parse_args()

def adb(*parts, timeout=40):
    return subprocess.run([str(args.adb), '-s', args.serial, *map(str, parts)], check=True, capture_output=True, timeout=timeout).stdout

assert args.serial.startswith('emulator-'), 'Only a dedicated emulator is supported'
assert adb('emu','avd','name').decode().splitlines()[0] == AVD, 'Wrong emulator: no changes made'
apk_path = adb('shell','pm','path',PKG).decode().strip().removeprefix('package:')
installed_sha = adb('shell','sha256sum',apk_path).decode().split()[0]
assert installed_sha == APK_SHA, 'Install the exact public 0.3.0 APK before capturing'
d = u2.connect(args.serial)
d.settings['wait_timeout'] = 12
d.screen_on()
assert (d.info['displayWidth'],d.info['displayHeight']) == (1080,2400)
assert adb('shell','getprop','persist.sys.locale').decode().strip() == 'ko-KR'
OUT = args.output
OUT.mkdir(parents=True, exist_ok=True)
EVIDENCE = ROOT / '.tools/manual/ui'; EVIDENCE.mkdir(parents=True, exist_ok=True)
manifest_path = OUT / 'manifest.json'
manifest = json.loads(manifest_path.read_text()) if manifest_path.exists() else {
    'version':'0.3.0', 'apk_sha256':APK_SHA,
    'release':'https://github.com/poyal/yeobaek/releases/tag/v0.3.0',
    'avd':AVD, 'api':36, 'resolution':[1080,2400], 'density':420,
    'locale':'ko-KR', 'font_scale':1.0, 'screens':[], 'checks':[],
}

def node(tag): return d(resourceId=tag)
def click(tag):
    node(tag).click(); time.sleep(.3)
def text(value):
    d(text=value).click(); time.sleep(.3)
def pick_document(name):
    if d(description='목록 보기').exists:
        d(description='목록 보기').click();time.sleep(.4)
    for _ in range(10):
        if d(text=name).exists:
            text(name);return
        adb('shell','input','swipe',540,1950,540,1000,600);time.sleep(.5)
    raise AssertionError(f'Document picker cannot find {name}')
def back():
    d.press('back'); time.sleep(.4)
def dump(): return ET.fromstring(d.dump_hierarchy())
def shot(name,title,*required):
    for tag in required: assert node(tag).wait(timeout=15), f'{name}: missing {tag}'
    time.sleep(.8)
    xml = d.dump_hierarchy()
    assert '애플리케이션이 응답하지 않음' not in xml
    (EVIDENCE / f'{name}.xml').write_text(xml,encoding='utf-8')
    png = adb('exec-out','screencap','-p')
    assert png.startswith(b'\x89PNG\r\n\x1a\n')
    assert struct.unpack('>II',png[16:24]) == (1080,2400)
    (OUT / f'{name}.png').write_bytes(png)
    record = dict(name=name,title=title,sha256=hashlib.sha256(png).hexdigest(),required=list(required),captured_at=datetime.datetime.now().astimezone().isoformat())
    manifest['screens'] = sorted([s for s in manifest['screens'] if s['name'] != name] + [record],key=lambda s:s['name'])
    save(); print('Captured',name,flush=True)
def save(): manifest_path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def check(name):
    if name not in manifest['checks']: manifest['checks'].append(name)
    save()
def scroll(tag,container,up=False):
    for _ in range(16):
        obj=node(tag)
        if obj.exists:
            b=obj.info['bounds']
            if b['bottom']-b['top'] > 35:
                time.sleep(.7)
                return obj
        b=node(container).info['bounds']; x=(b['left']+b['right'])//2
        y1,y2=b['top']+70,b['bottom']-70
        adb('shell','input','swipe',x,y1 if up else y2,x,y2 if up else y1,800);time.sleep(1)
    raise AssertionError(f'Cannot scroll to {tag}')
def enter_file(name):
    scroll('file_'+name,'library_list')
    for _ in range(3):
        node('file_'+name).click();time.sleep(1.2)
        if not node('library_list').exists: return
    raise AssertionError(f'File tap did not open {name}')
def input_text(tag,value):
    node(tag).set_text(value);time.sleep(.3)
    if d.info.get('currentPackageName') == PKG:
        # Back dismisses the IME only when it is actually showing.
        if 'mInputShown=true' in adb('shell','dumpsys','input_method').decode(): back()
def slider(tag,portion):
    b=node(tag).info['bounds']; d.click(b['left']+30+(b['right']-b['left']-60)*portion,(b['top']+b['bottom'])/2);time.sleep(.4)
def launch():
    adb('shell','am','start','-W','-n',f'{PKG}/.MainActivity');time.sleep(.6)
def connect(capture=True):
    click('connect_folder')
    if d(description='표시 위치').exists: d(description='표시 위치').click()
    elif d(description='Show roots').exists: d(description='Show roots').click()
    if d(text='다운로드').exists: text('다운로드')
    elif d(text='Downloads').exists: text('Downloads')
    if d(text='Download').exists: text('Download')
    text('여백 서재')
    if capture: shot('02-folder-picker','Android에서 시작 폴더 선택')
    select=d(resourceIdMatches='.*:id/action_menu_select')
    (select if select.exists else d(resourceId='android:id/button1')).click();time.sleep(.4)
    d(resourceId='android:id/button1').click()
    assert node('folder_refresh').wait(timeout=15)
def home():
    for _ in range(5):
        if node('nav_folder').exists: click('nav_folder');return
        back()
    raise AssertionError('Home not found')

def start():
    # This reset is confined to the dedicated AVD and the verified public APK.
    adb('shell','rm','-f',shlex.quote('/sdcard/Download/여백 서재/yeobaek-manual-backup.json'))
    adb('shell','am','force-stop',PKG);adb('shell','pm','clear',PKG);launch()
    shot('01-first-open','처음 실행과 시작 폴더 선택','connect_folder')
    connect();shot('03-library','폴더와 파일이 있는 홈','folder_refresh','nav_folder')
    check('처음 실행 → Android 폴더 권한 허용 → 실제 파일 목록')

def txt():
    enter_file('비가 그친 오후.txt')
    assert node('reader_footer').wait(timeout=30)
    shot('04-txt-reader','메뉴를 펼친 TXT 독서','reader_header','reader_footer')
    d.click(.5,.48);time.sleep(.4)
    assert not node('reader_header').exists
    shot('05-txt-focus','메뉴를 숨기고 본문 읽기','reader_body')
    d.click(.5,.48);click('reader_move');slider('move_slider',.36)
    shot('06-move','읽기 위치를 고른 뒤 이동','move_slider','move_apply')
    click('move_apply');time.sleep(.7);click('reader_bookmark');time.sleep(2)
    click('reader_bookmarks');shot('07-bookmarks','문장 위치를 보관하는 책갈피','bookmark_list');click('sheet_close')
    click('reader_search');input_text('search_input','문장');click('search_submit');time.sleep(.7)
    shot('08-txt-search','본문 검색과 결과 이동','search_input','search_results','search_return')
    click('search_return')
    if node('sheet_close').exists: click('sheet_close')

def txt_settings():
    click('reader_txt');click('scope_BOOK');click('font_larger')
    shot('09-quick-settings','이 책에만 글자 크기 적용','scope_BOOK','inherit_size','quick_font')
    click('quick_details');shot('10-txt-settings','글꼴과 본문 미리보기','txt_settings_screen');back()
    # Closing the full settings returns to the reader.
    if node('sheet_close').exists: click('sheet_close')
    click('reader_txt');click('paper_SEPIA');click('sheet_close');d.click(.5,.48)
    shot('11-sepia','세피아 본문','reader_body')
    d.click(.5,.48);click('reader_txt');click('paper_DARK');click('sheet_close');d.click(.5,.48)
    shot('12-dark-reader','다크 본문','reader_body')
    d.click(.5,.48);click('reader_txt');click('paper_FOLLOW_APP')
    scroll('reader_awake','quick_options')
    shot('13-screen-controls','독서 중 밝기·회전·화면 유지','reader_awake','rotation_PORTRAIT')
    click('sheet_close')
    click('reader_info');click('info_encoding')
    shot('14-encoding','글자가 깨질 때 인코딩 미리보기','encoding_apply');click('sheet_close')

def txt_resume():
    # Kill only after state persistence, then exercise the actual resume dialog.
    time.sleep(1.2);adb('shell','am','force-stop',PKG);launch()
    shot('15-resume','다음 실행에서 읽던 책 이어 읽기','resume_accept','resume_decline')
    click('resume_accept');assert node('reader_footer').wait(timeout=30)
    click('reader_back');click('nav_recent')
    shot('16-recent','최근 읽기에서 책 다시 열기','recent_list')
    click('nav_folder');click('folder_favorite')
    scroll('folder_search','library_list');input_text('folder_search','오후')
    scroll('file_비가 그친 오후.txt','library_list')
    shot('17-folder-search','현재 폴더 검색·정렬·읽기 상태','folder_search','sort_NAME','status_비가 그친 오후.txt')
    d(description='검색 지우기').click()
    check('TXT 메뉴 숨김·위치 이동·책갈피·검색·개별 설정·인코딩 미리보기·프로세스 종료 후 이어 읽기')

def pdf():
    if not node('pdf_footer').exists: enter_file('산책의 기록.pdf')
    assert node('pdf_footer').wait(timeout=30)
    shot('18-pdf-reader','한글 PDF를 원래 편집 그대로 읽기','pdf_footer','pdf_page')
    click('pdf_settings');shot('19-pdf-settings','PDF 화면 맞춤과 색상 반전','pdf_fit_WIDTH','pdf_inversion_switch')
    click('sheet_close');click('pdf_search');input_text('pdf_search_input','문장');click('pdf_search_submit');time.sleep(1)
    shot('20-pdf-search','PDF 텍스트 검색','pdf_search_results','pdf_search_return')
    assert d(textMatches=r'[1-9][0-9]*개 결과.*').exists
    click('pdf_search_return');click('pdf_bookmark');click('pdf_move');input_text('pdf_page_input','2');click('pdf_move_apply')
    assert '2 /' in node('pdf_page').get_text()
    click('pdf_back');check('한글 PDF 렌더링·검색·페이지 이동·책갈피')

def comic():
    enter_file('작은 정원.cbz');assert node('image_footer').wait(timeout=30)
    click('image_settings');click('scope_BOOK');click('image_mode_PAGED');click('sheet_close');time.sleep(1)
    shot('21-comic-spread','가로로 긴 펼침면 전체','image_page','image_footer')
    click('image_settings');click('image_split');click('sheet_close');time.sleep(1)
    # The native view reports the segment label after a page gesture.
    d.swipe(.85,.5,.15,.5,.35);time.sleep(.5)
    d.swipe(.15,.5,.85,.5,.35);time.sleep(.7)
    comic_details()

def comic_details():
    assert '왼쪽' in node('image_page').get_text()
    shot('22-comic-left','펼침면 왼쪽부터 읽기','image_page')
    d.swipe(.85,.5,.15,.5,.35);time.sleep(.7)
    assert '오른쪽' in node('image_page').get_text()
    shot('23-comic-right','같은 페이지의 오른쪽 이어 읽기','image_page')
    click('image_bookmark');click('image_settings');scroll('image_crop_edit','quick_options').click();time.sleep(.5)
    slider('crop_left',.15)
    shot('24-crop','미리보며 여백 직접 조절','crop_options','crop_left')
    scroll('crop_apply','crop_options').click();time.sleep(.5)
    click('image_move');shot('25-thumbnails','페이지 번호와 썸네일로 이동','image_thumbnails','image_page_input')
    click('image_thumbnail_1');time.sleep(.7)
    if not node('reading_end_dialog').wait(timeout=2): d.swipe(.85,.5,.15,.5,.35)
    comic_end()

def comic_end():
    shot('37-reading-end','마지막 항목까지 읽은 뒤 나가기','reading_end_dialog','reading_end_exit')
    click('reading_end_exit')
    check('CBZ 펼침면 전체·왼쪽/오른쪽 분할·책갈피·자르기 적용·썸네일 이동')

def backup():
    click('nav_settings');shot('30-settings','설정 메뉴','settings_general','settings_updates')
    click('settings_general');shot('31-theme','앱 테마와 시작 폴더','theme_LIGHT','theme_DARK')
    scroll('backup_import','general_settings_options')
    shot('26-backup','독서 기록 백업 저장과 가져오기','backup_export','backup_import')
    click('backup_export')
    d(className='android.widget.EditText').set_text('yeobaek-manual-backup.json')
    if 'mInputShown=true' in adb('shell','dumpsys','input_method').decode(): back()
    save_button=d(resourceIdMatches='.*:id/action_menu_save')
    (save_button if save_button.exists else d(resourceId='android:id/button1')).click();time.sleep(.5)
    if d(resourceId='android:id/button1').exists: d(resourceId='android:id/button1').click()
    assert node('backup_status').wait(timeout=15)
    assert '저장' in node('backup_status').get_text()
    click('backup_import');backup_import()

def backup_import():
    pick_document('yeobaek-manual-backup.json')
    shot('27-restore-preview','기존 기록을 유지하는 가져오기 미리보기','backup_restore_confirm')
    click('backup_restore_confirm');time.sleep(.6)
    assert '복원했습니다' in node('backup_status').get_text()
    check('Android 저장기로 JSON 백업 → 같은 앱에 가져오기 → 기존 기록 보존 안내 및 복원 성공')
    scroll('clear_cache','general_settings_options',up=True)
    shot('32-storage','원본과 독서 기록을 보존하는 캐시 정리','clear_cache','disconnect')
    back();click('settings_updates');click('update_check')
    assert d(text='확인 중…').wait_gone(timeout=30)
    shot('33-update','현재 버전과 업데이트 확인','update_check')
    home()

def restore():
    adb('shell','am','force-stop',PKG);adb('shell','pm','clear',PKG);launch()
    click('nav_settings');click('settings_general');scroll('backup_import','general_settings_options');click('backup_import')
    pick_document('yeobaek-manual-backup.json')
    shot('28-fresh-restore','빈 앱에 독서 기록 가져오기','backup_restore_confirm')
    assert d(textContains='겹치는 기록 0권').exists
    click('backup_restore_confirm');time.sleep(.5)
    assert '복원했습니다' in node('backup_status').get_text()
    back();click('nav_recent');click('recent_비가 그친 오후.txt')
    shot('29-reconnect','기록은 남겨 두고 원본 파일 다시 찾기','find_missing_book')
    click('find_missing_book')
    # The picker remembers the backup directory; TXT is next to that backup.
    if not d(text='비가 그친 오후.txt').exists:
        if d(description='표시 위치').exists: d(description='표시 위치').click()
        if d(text='다운로드').exists: text('다운로드')
        elif d(text='Downloads').exists: text('Downloads')
        if d(text='Download').exists: text('Download')
        if d(text='여백 서재').exists: text('여백 서재')
    pick_document('비가 그친 오후.txt');assert node('reader_footer').wait(timeout=30)
    assert float(node('reader_percent').get_text().removesuffix('%')) > 10
    click('reader_bookmarks');assert node('bookmark_list').wait(timeout=10);click('sheet_close');click('reader_back')
    click('nav_folder');connect(capture=False)
    check('빈 앱 복원 → 겹치는 기록 0권 → 원본 다시 찾기 → TXT 위치와 책갈피 유지')

def finish():
    # A populated library makes a useful first impression in the manual.
    scroll('folder_창가의 장면','library_list',up=True).click();time.sleep(.4)
    enter_file('01.png');assert node('image_footer').wait(timeout=30)
    shot('34-image-folder','폴더 속 이미지들을 한 권으로 읽기','image_page','image_footer')
    click('image_back');home()
    # Return to the configured root and reopen the novel for the hero state.
    if not node('file_비가 그친 오후.txt').exists:
        if node('folder_up').exists: click('folder_up')
        else:
            adb('shell','am','force-stop',PKG);launch()
    enter_file('비가 그친 오후.txt');assert node('reader_footer').wait(timeout=30)
    click('reader_back')
    for _ in range(4): d.swipe(.5,.3,.5,.82,.25)
    shot('35-library-reading','읽던 책과 내 폴더가 있는 홈','continue_card','nav_folder')
    click('nav_settings');click('settings_general');click('theme_DARK');back();click('nav_folder')
    shot('36-dark-library','다크 테마의 홈','continue_card','nav_folder')
    click('nav_settings');click('settings_general');click('theme_LIGHT');back();click('nav_folder')
    check('이미지 폴더 열기·앱 라이트/다크 테마')

STAGES = {'start':start,'txt':txt,'txt-settings':txt_settings,'txt-resume':txt_resume,'pdf':pdf,'comic':comic,'backup':backup,'restore':restore,'finish':finish}
try:
    if args.stage == 'inspect':
        for n in dump().iter('node'):
            if n.get('resource-id') or n.get('text') or n.get('content-desc'):
                print(n.get('resource-id'),repr(n.get('text')),repr(n.get('content-desc')),n.get('bounds'))
    elif args.stage == 'all':
        manifest['screens']=[];manifest['checks']=[];save()
        for function in STAGES.values(): function()
    else:
        {**STAGES,'comic-details':comic_details,'comic-end':comic_end,'backup-import':backup_import}[args.stage]()
except Exception:
    (EVIDENCE / 'failure.png').write_bytes(adb('exec-out','screencap','-p'))
    raise
finally:
    # Wait for this client's server to exit before a subsequent stage connects.
    d.stop_uiautomator(wait=True)
