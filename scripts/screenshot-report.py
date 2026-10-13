#!/usr/bin/env python3
"""Collect verified PNGs, check required controls, and create an offline review gallery."""
import argparse, hashlib, html, json, shutil, xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image, ImageDraw

p = argparse.ArgumentParser()
p.add_argument('runs', nargs='+', type=Path)
p.add_argument('--output', type=Path, default=Path('docs/releases/0.1.0-screens'))
args = p.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
required = {
 '01': ['connect_folder', 'nav_folder', 'nav_recent', 'nav_settings'],
 '05': ['reader_header', 'reader_percent', 'reader_bookmark', 'reader_search', 'reader_bookmarks', 'reader_txt', 'reader_info', 'reader_move'],
 '08': ['move_slider', 'move_apply', 'move_cancel'],
 '09': ['move_slider', 'move_apply'],
 '12': ['bookmark_delete'],
 '13': ['font_larger', 'font_size', 'paper_FOLLOW_APP'],
 '16': ['search_input', 'search_submit', 'search_return'],
 '17': ['search_previous', 'search_next', 'search_results'],
 '20': ['info_encoding'], '21': ['encoding_apply'],
 '22': ['resume_accept', 'resume_decline'], '25': ['forget_confirm'],
 '27': ['clear_cache', 'change_folder', 'disconnect'], '28': ['disconnect_confirm'],
 '34': ['error_encoding'], '35': ['encoding_apply'], '40': ['find_missing_book'], '41': ['change_accept'],
}
entries, evidence = [], []
for run in args.runs:
    result = json.loads((run/'summary.json').read_text())
    assert result['status'] == 'PASS', f'통과한 실행만 수집합니다: {run}'
    meta = json.loads((run/'manifest.json').read_text())
    group = f"api{meta['api']}-{meta['suite']}-{meta['theme'].lower()}"
    if meta['suite'] == 'restore': group += '-' + run.name.split('-')[1]
    target = args.output/'screenshots'/group
    target.mkdir(parents=True, exist_ok=True)
    functional = meta['suite'] in ('e2e', 'recovery')
    source = 'captures' if functional else 'restore' if meta['suite'] == 'restore' else 'performance'
    files = sorted((run/'device'/source).rglob('*.png'))
    if functional:
        expected = set(range(1,39)) if meta['suite'] == 'e2e' else set(range(39,51))
        actual = {int(f.stem.split('-')[0]) for f in files if not f.name.startswith('FAIL')}
        assert expected <= actual, f'누락된 화면: {group}: {expected-actual}'
    else:
        expected_count = {'load':15, 'soak':2, 'repeat':1, 'restore':1}[meta['suite']]
        assert len(files) == expected_count, f'{group}: {len(files)} != {expected_count}'
    assert not any(f.name.startswith('FAIL') for f in files), f'실패 캡처가 섞였습니다: {group}'
    for f in files:
        xml = f.with_suffix('.xml')
        nodes = ET.parse(xml).getroot().iter('node')
        tags = {n.get('resource-id') for n in nodes}
        number = f.stem[:2] if functional else ''
        for tag in required.get(number, []):
            assert tag in tags, f'{f.name}: 필수 요소 {tag} 누락'
        if number == '06': assert 'reader_header' not in tags and 'reader_footer' not in tags
        if meta['suite'] in ('load', 'soak'):
            assert ('error_close' if f.stem == 'source-limit' else 'reader_body') in tags, f'{f.name}: 본문/제한 안내 누락'
        if meta['suite'] == 'load' and f.stem != 'source-limit':
            title = f.stem.removesuffix('-80percent')
            texts = {n.get('text') for n in ET.parse(xml).getroot().iter('node')}
            assert title in texts, f'{f.name}: 다른 파일의 본문을 캡처했습니다'
        if number in ('01', '05', '15'):
            expected_color = (242,231,212) if number == '15' else (29,33,28) if meta['theme']=='DARK' else (248,245,239)
            actual_color = Image.open(f).convert('RGB').getpixel((5,250))
            assert actual_color == expected_color, f'{f.name}: 배경색 {actual_color} != {expected_color}'
        dest = target/f.name; shutil.copy2(f, dest)
        entries.append({'group':group,'name':f.stem,'path':str(dest.relative_to(args.output)), 'sha256':hashlib.sha256(f.read_bytes()).hexdigest()})
    evidence.append(dict(group=group, apk_sha256=meta['apk_sha256'], api=meta['api'], count=len(files), controls='PASS', source_run=run.name))

for group in sorted({e['group'] for e in entries}):
    items = [e for e in entries if e['group']==group]
    for start in range(0,len(items),12):
        batch=items[start:start+12]
        page=Image.new('RGB',(1200,490*((len(batch)+5)//6)), '#deded8');draw=ImageDraw.Draw(page)
        for i,e in enumerate(batch):
            im=Image.open(args.output/e['path']).convert('RGB'); im.thumbnail((190,445))
            x=i%6*200;y=i//6*490; page.paste(im,(x,y));draw.text((x,y+450),e['name'][:29],fill='black')
        page.save(args.output/f'{group}-contact-{start//12+1}.png')

cards=''.join(f'<figure><a href="{html.escape(e["path"])}"><img loading="lazy" src="{html.escape(e["path"])}" alt="{html.escape(e["name"])}"></a><figcaption>{html.escape(e["group"])} / {html.escape(e["name"])}</figcaption></figure>' for e in entries)
(args.output/'index.html').write_text('<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>여백 실제 APK 화면 검수</title><style>body{background:#f8f5ef;color:#30372f;font:15px system-ui;margin:32px}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:20px}figure{margin:0}img{width:100%;height:520px;object-fit:contain;object-position:top;background:#e3e8d7}figcaption{padding:12px 0;overflow-wrap:anywhere}a{color:inherit}</style><h1>여백 실제 APK 화면 검수</h1><p>실제 자동 검사에서 촬영한 화면입니다. 각 이미지를 누르면 원본 크기로 확인할 수 있습니다. 필수 화면·조작 요소·대표 배경색을 자동 대조하고, 별도 검수 기록에서 잘림과 구성을 확인합니다.</p><main>'+cards+'</main></html>')
(args.output/'manifest.json').write_text(json.dumps({'runs':evidence,'screens':entries},ensure_ascii=False,indent=2))
print(f'{len(entries)} screenshots verified: {args.output}/index.html')
