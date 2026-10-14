#!/usr/bin/env python3
"""Original, deterministic images/archives for reader regression tests (Pillow required)."""
import argparse
import io
from pathlib import Path
import struct
import subprocess
import tempfile
import zipfile
import zlib
from PIL import Image, ImageDraw


def rar4_stored(path, files):
    # RAR 4 stored blocks: header CRC is the low 16 bits of CRC32 of TYPE..payload.
    def header(kind, flags, payload):
        value = struct.pack('<BHH', kind, flags, 7 + len(payload)) + payload
        return struct.pack('<H', zlib.crc32(value) & 0xffff) + value
    with path.open('wb') as output:
        output.write(b'Rar!\x1a\x07\x00')
        output.write(header(0x73, 0, b'\x00' * 6))
        for name, data in files:
            encoded = name.encode('ascii')
            body = struct.pack('<IIBIIBBHI', len(data), len(data), 3, zlib.crc32(data), 0, 20, 0x30, len(encoded), 0o100644) + encoded
            output.write(header(0x74, 0x8000, body)); output.write(data)
        output.write(header(0x7b, 0, b''))


def generate(out, seven, rar):
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as work:
        images = Path(work)
        for number, mode, extension in [(1, 'RGB', 'jpg'), (2, 'RGBA', 'png'), (10, 'RGB', 'webp'), (11, 'RGB', 'bmp'), (12, 'RGB', 'gif')]:
            im = Image.new(mode, (240, 320), (255, 255, 255, 0) if mode == 'RGBA' else 'white')
            draw = ImageDraw.Draw(im)
            draw.rectangle((10, 10, 110, 150), fill=(200, 30, 60, 255) if mode == 'RGBA' else (200, 30, 60))
            draw.text((20, 200), f'PAGE {number}', fill='black', font_size=22)
            im.save(images / f'{number}.{extension}')
        wide = Image.new('RGB', (480, 240), '#559944'); wide.save(images / '13.jpg')
        for orientation in range(1, 9):
            im = Image.new('RGB', (120, 80), 'white')
            ImageDraw.Draw(im).rectangle((0, 0, 39, 29), fill='red')
            exif = Image.Exif(); exif[274] = orientation
            im.save(images / f'orientation-{orientation}.jpg', quality=100, subsampling=0, exif=exif)
        with zipfile.ZipFile(out / 'mixed.cbz', 'w', zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(images.iterdir(), reverse=True): archive.write(file, '권1/' + file.name)
            archive.writestr('../escape.jpg', b'bad'); archive.writestr('__MACOSX/._1.jpg', b'bad')
            archive.writestr('readme.txt', 'Not a page'); archive.writestr('권2/1.png', (images / '2.png').read_bytes())
        for extension, args in [('cb7', [str(seven), 'a', '-t7z', '-mx=3', '-ms=on']), ('cbr', [str(rar), 'a', '-ma5', '-m3', '-s', '-ep1'])]:
            subprocess.run([*args, str((out / f'mixed.{extension}').resolve()), *map(str, sorted(images.iterdir()))], check=True, stdout=subprocess.DEVNULL)
        rar4_stored(out / 'stored-rar4.cbr', [(p.name, p.read_bytes()) for p in sorted(images.iterdir())])
        subprocess.run([str(seven), 'a', '-t7z', '-pfixture', '-mhe=on', str((out / 'locked.7z').resolve()), str(images / '1.jpg')], check=True, stdout=subprocess.DEVNULL)
        with zipfile.ZipFile(out / 'empty.zip', 'w') as archive: archive.writestr('readme.txt', 'No images')
        with zipfile.ZipFile(out / 'broken-image.zip', 'w') as archive:
            archive.writestr('1.jpg', b'broken'); archive.write(images / '1.jpg', '2.jpg')
        (out / 'broken.zip').write_bytes(b'PK\x03\x04incomplete')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', type=Path, default=Path('app/src/androidTest/assets/images'))
    parser.add_argument('--seven', type=Path, default=Path('.tools/image-deps/7zip/7zz'))
    parser.add_argument('--rar', type=Path, default=Path('.tools/image-deps/rar/rar/rar'))
    args = parser.parse_args()
    generate(args.out, args.seven, args.rar)
