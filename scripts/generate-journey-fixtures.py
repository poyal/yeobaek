"""Synthetic, numbered content for cross-format UI journeys; no user books."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import runpy
import shutil
import zipfile
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent


def generate(out):
    out.mkdir(parents=True, exist_ok=True)
    (out / '01-short.txt').write_text('\n'.join(f'LINE {i:04d} 바람' for i in range(600)), encoding='utf-8')
    (out / '02-story.txt').write_text('\n\n'.join(f'ROW {i:04d} needle 한글 English · 문장의 위치를 확인합니다. ' * 4 for i in range(250)), encoding='utf-8')
    pdf = runpy.run_path(str(ROOT / 'scripts/generate-pdf-fixtures.py'))['pdf']
    pdf(out / '03-book.pdf', 120)
    pdf(out / '08-scanned.pdf', 3, scanned=True)
    images = out / 'Images'
    images.mkdir(exist_ok=True)
    pages = []
    for number in range(1, 25):
        wide = number in (3, 24)
        im = Image.new('RGB', (1200, 600) if wide else (600, 900), 'white')
        draw = ImageDraw.Draw(im)
        draw.rectangle((30, 30, im.width - 30, im.height - 30), fill=('#d9e8cf' if number % 2 else '#e8dacf'))
        draw.text((60, 100), f'PAGE {number:02d}', fill='black', font_size=64)
        if wide:
            draw.text((60, 250), 'LEFT', fill='black', font_size=48)
            draw.text((700, 250), 'RIGHT', fill='black', font_size=48)
        stream = io.BytesIO(); im.save(stream, 'PNG')
        data = stream.getvalue()
        (images / f'{number}.png').write_bytes(data)
        pages.append((f'chapter{1 if number <= 12 else 2}/{number}.png', data))
    with zipfile.ZipFile(out / '04-comic.cbz', 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data in reversed(pages):
            info = zipfile.ZipInfo(name, (2020, 1, 1, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
    for source, target in [('Yeobaek-Reflow.epub', '05-reflow.epub'), ('Yeobaek-Fixed.epub', '06-fixed.epub')]:
        shutil.copyfile(ROOT / 'e2e/src/main/assets/epub' / source, out / target)
    for name in ('mixed.cbr', 'mixed.cb7', 'stored-rar4.cbr', 'broken.zip', 'broken-image.zip', 'locked.7z'):
        folder = out / 'Archives'; folder.mkdir(exist_ok=True)
        shutil.copyfile(ROOT / 'app/src/androidTest/assets/images' / name, folder / name)
    for name in ('broken.pdf', 'locked.pdf'):
        folder = out / 'Errors'; folder.mkdir(exist_ok=True)
        shutil.copyfile(ROOT / 'app/src/androidTest/assets/pdf' / name, folder / name)
    next_folder = out / 'Next'; next_folder.mkdir(exist_ok=True)
    (next_folder / '01.txt').write_text('FIRST BOOK\nLAST LINE\n', encoding='utf-8')
    pdf(next_folder / '02.pdf', 1)
    with zipfile.ZipFile(next_folder / '03.cbz', 'w') as z:
        z.writestr(zipfile.ZipInfo('1.png', (2020, 1, 1, 0, 0, 0)), pages[0][1])
    shutil.copyfile(out / '06-fixed.epub', next_folder / '04.epub')
    (out / 'Empty').mkdir(exist_ok=True)
    manifest = [dict(name=f.relative_to(out).as_posix(), bytes=f.stat().st_size,
                     sha256=hashlib.sha256(f.read_bytes()).hexdigest())
                for f in sorted(out.rglob('*')) if f.is_file() and f.name != 'manifest.json']
    (out / 'manifest.json').write_text(json.dumps(dict(seed=1, fixtures=manifest), indent=2), encoding='utf-8')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / '.tools/fixtures/YeobaekQA')
    result = generate(parser.parse_args().output)
    print(f'{len(result)} synthetic files prepared')
