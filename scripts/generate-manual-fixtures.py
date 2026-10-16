#!/usr/bin/env python3
"""Create original, deterministic reading material for the user manual (no personal books)."""
import argparse
import hashlib
import io
import json
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=ROOT / '.tools/manual/여백 서재')
parser.add_argument('--pdf-font', type=Path, required=True, help='OFL Noto Serif KR TrueType font')
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
paper, ink, olive, soft = '#f8f5ef', '#30372f', '#536348', '#e3e8d7'

chapters = [
    ('비가 그친 오후', [
        '창밖에서 다시 바람이 불었다. 젖은 잎이 서로 닿는 소리를 들으며, 나는 다음 문장으로 천천히 시선을 옮겼다.',
        '비가 그친 뒤의 골목은 평소보다 조금 넓어 보였다. 가게 앞에 놓인 작은 화분들에는 아직 물방울이 매달려 있었다. 나는 서두르지 않고 그 사이를 걸었다.',
        '익숙한 길에도 처음 보는 장면이 있었다. 닫힌 창문에 비친 하늘, 누군가 반듯하게 접어 둔 우산, 빵집 문틈으로 흘러나오는 따뜻한 냄새 같은 것들.',
        '“오늘은 조금 늦어도 괜찮겠네.”',
        '누구에게 하는 말인지도 모른 채 작게 중얼거렸다. 주머니 속 휴대전화는 조용했고, 다음 모퉁이까지는 아직 걸어갈 시간이 남아 있었다.',
    ]),
    ('작은 책방', [
        '골목 끝 책방은 문을 반쯤 열어 두고 있었다. 문을 밀자 작은 종이 울렸다. 주인은 읽던 책 사이에 종이 한 장을 끼우고 고개를 들었다.',
        '창가에는 낮은 의자 두 개와 작은 탁자가 있었다. 나는 제목이 마음에 드는 책을 한 권 골라 앉았다. 첫 문장은 오래전부터 나를 기다리고 있었던 것처럼 자연스럽게 읽혔다.',
        '유리창에 남아 있던 물방울이 한 줄로 흘러내렸다. 거리에서는 우산을 접는 소리가 났다. 몇 장을 읽었는지는 중요하지 않았다. 이 자리에 조금 더 앉아 있고 싶었다.',
    ]),
    ('돌아오는 길', [
        '책방을 나설 때는 해가 낮은 담장 위에 걸려 있었다. 나는 조금 전 읽은 문장을 기억하려다가 그만두었다. 마음에 남는 것은 문장보다 그 문장을 읽던 오후의 빛일지도 몰랐다.',
        '돌아가는 길에는 아까 보지 못한 화분이 하나 더 눈에 들어왔다. 새 잎은 손톱만큼 작았고, 줄기 끝에 조심스럽게 매달려 있었다.',
        '집에 도착해 창문을 열었다. 바람이 책장을 한 장 넘겼다. 내일은 여기서부터 다시 읽으면 되겠다고 생각했다.',
    ]),
]
story = '\n\n'.join(title + '\n\n' + '\n\n'.join(paragraphs) for title, paragraphs in chapters) + '\n'
(args.output / '비가 그친 오후.txt').write_text(story, encoding='utf-8')
(args.output / '오래된 편지.txt').write_bytes(('오래된 편지\n\n서랍을 정리하다가 오래된 편지를 찾았습니다.\n종이 위의 글씨는 여전히 또렷했습니다.\n' * 8).encode('cp949'))
(args.output / '산문').mkdir(exist_ok=True)
(args.output / '산문/숲으로 가는 길.txt').write_text('숲으로 가는 길\n\n나무 사이로 아침 햇살이 들어왔습니다.\n천천히 걸으며 잎이 흔들리는 소리를 들었습니다.\n' * 8, encoding='utf-8')

# Embedded, searchable Korean text. The font file stays outside the repository.
pdfmetrics.registerFont(TTFont('ManualSerif', str(args.pdf_font)))
pdf = canvas.Canvas(str(args.output / '산책의 기록.pdf'), pagesize=(480, 680), invariant=1)
pdf.setTitle('산책의 기록 · 여백 예제')
pdf.setAuthor('여백')
for page, (title, paragraphs) in enumerate(chapters, 1):
    pdf.setFillColor(paper); pdf.rect(0, 0, 480, 680, fill=1, stroke=0)
    pdf.setFillColor(olive); pdf.setFont('ManualSerif', 10)
    pdf.drawString(44, 626, '산책의 기록  /  여백에서 읽는 작은 산문')
    pdf.setStrokeColor(olive); pdf.setLineWidth(.6); pdf.line(44, 608, 436, 608)
    pdf.setFillColor(ink); pdf.setFont('ManualSerif', 27); pdf.drawString(44, 558, title)
    y = 506
    pdf.setFont('ManualSerif', 13)
    for paragraph in paragraphs[:3]:
        line = ''
        for char in paragraph:
            if pdfmetrics.stringWidth(line + char, 'ManualSerif', 13) > 390:
                pdf.drawString(44, y, line); y -= 25; line = ''
            line += char
        pdf.drawString(44, y, line); y -= 43
    pdf.setFillColor(olive); pdf.setFont('ManualSerif', 10)
    pdf.drawString(44, 38, '사용법을 위한 창작 예제'); pdf.drawRightString(436, 38, str(page))
    pdf.showPage()
pdf.save()

# Simple original line drawings: a window, a plant, and an open book.
# Kept deterministic so the same spread and crop can be recaptured.
font_path = ROOT / 'app/src/main/res/font/noto_serif_kr.otf'
def font(size): return ImageFont.truetype(str(font_path), size)
def scene(index):
    im = Image.new('RGB', (900, 1200), paper); d = ImageDraw.Draw(im)
    d.text((65, 55), f'작은 정원   {index:02}', font=font(28), fill=olive)
    d.line((65, 110, 835, 110), fill=olive, width=2)
    if index == 1:
        d.rectangle((70, 155, 830, 795), fill='#e8ede1', outline=ink, width=4)
        d.ellipse((590, 205, 710, 325), fill='#d8b879')
        d.polygon([(70,650),(270,450),(440,630),(640,430),(830,650),(830,795),(70,795)], fill='#b5c3a5')
        for x in (70, 450, 830): d.line((x,155,x,795), fill=ink, width=6)
        d.line((70,475,830,475), fill=ink, width=6)
        caption = '창문을 열자,\n작은 정원이 아침을 건넸다.'
    elif index == 2:
        d.rectangle((70,155,830,795), fill='#ede6d5', outline=ink, width=4)
        d.line((110,710,790,710), fill=ink, width=4)
        d.polygon([(330,570),(570,570),(535,710),(365,710)], fill='#b88f70', outline=ink, width=4)
        d.line((450,570,450,300), fill=olive, width=7)
        for box in ((310,320,450,400),(450,390,600,470),(330,470,450,540),(440,250,530,350)):
            d.ellipse(box, fill=olive)
        caption = '어제보다 조금 자란 잎.\n서두르지 않아도 자라는 것들.'
    else:
        d.rectangle((70,155,830,795), fill='#e3e8d7', outline=ink, width=4)
        d.ellipse((580,205,710,335), fill='#d8b879')
        d.polygon([(150,410),(430,450),(750,380),(750,650),(430,720),(150,680)], fill='#fffdf8', outline=ink, width=4)
        d.line((430,450,430,720), fill=ink, width=3)
        for dy in range(0,150,30):
            d.line((200,465+dy,385,490+dy), fill=olive, width=3)
            d.line((480,490+dy,700,440+dy), fill=olive, width=3)
        caption = '책을 펼치고, 한 문장을 읽었다.\n오늘의 속도는 이만하면 좋았다.'
    d.multiline_text((70,865), caption, font=font(33), fill=ink, spacing=20)
    d.text((70,1090), '여백 · 사용 안내를 위한 창작 그림', font=font(20), fill=olive)
    return im

images = args.output / '창가의 장면'; images.mkdir(exist_ok=True)
for index in range(1,4): scene(index).save(images / f'{index:02}.png', optimize=True)
spread = Image.new('RGB', (1800,1200), paper)
spread.paste(scene(1),(0,0)); spread.paste(scene(2),(900,0))
with zipfile.ZipFile(args.output / '작은 정원.cbz', 'w', zipfile.ZIP_DEFLATED) as archive:
    for name, im in [('01.png',spread), ('02.png',scene(3))]:
        data = io.BytesIO(); im.save(data,format='PNG',optimize=True)
        entry = zipfile.ZipInfo(name, date_time=(2026,10,16,0,0,0)); entry.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(entry,data.getvalue())
manifest = [{'path': f.relative_to(args.output).as_posix(), 'sha256': hashlib.sha256(f.read_bytes()).hexdigest(), 'bytes': f.stat().st_size} for f in sorted(args.output.rglob('*')) if f.is_file()]
(args.output.parent / 'fixtures.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(f'{len(manifest)} original fixtures: {args.output}')
