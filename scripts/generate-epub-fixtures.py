from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED, ZIP_STORED
import argparse

parser = argparse.ArgumentParser(description='Create original EPUB samples for device journeys.')
parser.add_argument('--output', default='.tools/fixtures/YeobaekEPUB')
out = Path(parser.parse_args().output)
out.mkdir(parents=True, exist_ok=True)
for fixed in (False, True):
    name = 'Yeobaek-Fixed.epub' if fixed else 'Yeobaek-Reflow.epub'
    with ZipFile(out / name, 'w', compression=ZIP_DEFLATED) as z:
        z.writestr('mimetype', 'application/epub+zip', compress_type=ZIP_STORED)
        z.writestr('META-INF/container.xml', '<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        items = ''.join(f'<item id="c{i}" href="c{i}.xhtml" media-type="application/xhtml+xml"/>' for i in range(1, 4))
        spine = ''.join(f'<itemref idref="c{i}"/>' for i in range(1, 4))
        layout = '<meta property="rendition:layout">pre-paginated</meta>' if fixed else ''
        z.writestr('EPUB/package.opf', f'''<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="id" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:uuid:efc61231-604c-4eaf-ace1-2ea7c7caaafa</dc:identifier><dc:title>여백의 EPUB</dc:title><dc:language>ko</dc:language><meta property="dcterms:modified">2026-10-17T00:00:00Z</meta>{layout}</metadata><manifest>{items}<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine>{spine}</spine></package>''')
        links = ''.join(f'<li><a href="c{i}.xhtml">{i}장 · 여백의 시간</a></li>' for i in range(1, 4))
        z.writestr('EPUB/nav.xhtml', f'<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>목차</title></head><body><nav epub:type="toc"><ol>{links}</ol></nav></body></html>')
        for i in range(1, 4):
            body = f'<h1>{i}장 · 여백의 시간</h1>' + ''.join(f'<p id="p{n}">{n}. 창문 너머로 아침이 천천히 밝아 왔다. 서둘러 넘기던 책장을 멈추고 문장 사이의 여백을 바라보았다. 오늘은 이 작은 이야기와 함께 걷기로 했다.</p>' for n in range(1, 81))
            body = f'<p><a href="c{(i % 3) + 1}.xhtml#p15">다음 장의 각주로 이동</a></p>' + body
            viewport = ''
            style = 'body{font-family:serif} p{line-height:1.85} h1{font-size:1.5em}'
            if fixed:
                viewport = '<meta name="viewport" content="width=600,height=800"/>'
                style = 'body{margin:0;width:600px;height:800px;background:#eee8dc}'
                body = f'<svg xmlns="http://www.w3.org/2000/svg" width="600" height="800"><rect width="600" height="800" fill="#eee8dc"/><circle cx="300" cy="280" r="130" fill="#617255"/><path d="M80 490 Q300 350 520 490" fill="none" stroke="#617255" stroke-width="4"/><text x="60" y="600" font-size="32">{i}쪽 · 여백의 그림책</text><text x="60" y="650" font-size="20">그림을 확대하며 천천히 읽습니다.</text></svg>'
            z.writestr(f'EPUB/c{i}.xhtml', f'<html xmlns="http://www.w3.org/1999/xhtml"><head><title>{i}장</title>{viewport}<style>{style}</style></head><body>{body}</body></html>')
    print(out / name)
