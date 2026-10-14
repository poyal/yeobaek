#!/usr/bin/env python3
"""Stream reproducible, image-backed PDFs. Every large stream is used by its page."""
import argparse
import hashlib
import io
import json
from pathlib import Path
from PIL import Image, ImageDraw

MIB = 1024 * 1024


def generate(path, target_mib, searchable):
    offsets = [0, 0, 0, 0]
    pages = []
    image_bytes = 0
    with path.open('wb') as out:
        out.write(b'%PDF-1.7\n%\xe2\xe3\xcf\xd3\n')

        def obj(number, data):
            while len(offsets) <= number:
                offsets.append(0)
            offsets[number] = out.tell()
            out.write(f'{number} 0 obj\n'.encode() + data + b'\nendobj\n')

        def stream(number, header, data):
            obj(number, header + f' /Length {len(data)} >>\nstream\n'.encode() + data + b'\nendstream')

        while out.tell() < target_mib * MIB:
            index = len(pages)
            # Unique JPEGs, no shared one-image trick, unused streams, or padding.
            width, height = 2048, 2816
            pixels = hashlib.shake_256(f'yeobaek-pdf-load-v1-{target_mib}-{index}'.encode()).digest(width * height * 3)
            image = Image.frombytes('RGB', (width, height), pixels)
            draw = ImageDraw.Draw(image)
            draw.rectangle((64, 64, 1984, 280), fill='white')
            draw.text((100, 110), f'Yeobaek original image fixture / page {index + 1}', fill='black', font_size=48)
            encoded = io.BytesIO()
            image.save(encoded, format='JPEG', quality=88, subsampling=0, optimize=False)
            jpeg = encoded.getvalue()
            image_bytes += len(jpeg)
            image_id = 4 + index * 3
            content_id, page_id = image_id + 1, image_id + 2
            stream(image_id, f'<< /Type /XObject /Subtype /Image /Width {width} /Height {height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode'.encode(), jpeg)
            content = b'q 600 0 0 740 0 0 cm /Image Do Q\n'
            if searchable:
                content += f'BT /F1 18 Tf 24 766 Td (LOADTOKEN page {index + 1}) Tj ET\n'.encode()
            stream(content_id, b'<<', content)
            obj(page_id, f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << /Font << /F1 3 0 R >> /XObject << /Image {image_id} 0 R >> >> /Contents {content_id} 0 R >>'.encode())
            pages.append(page_id)
        obj(1, b'<< /Type /Catalog /Pages 2 0 R >>')
        obj(2, f'<< /Type /Pages /Count {len(pages)} /Kids ['.encode() + b' '.join(f'{p} 0 R'.encode() for p in pages) + b'] >>')
        obj(3, b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
        xref = out.tell()
        out.write(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode())
        for offset in offsets[1:]:
            out.write(f'{offset:010d} 00000 n \n'.encode())
        out.write(f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(MIB), b''):
            digest.update(chunk)
    return dict(file=path.name, bytes=path.stat().st_size, pages=len(pages), searchable=searchable,
                image_width=width, image_height=height, unique_images=len(pages), image_stream_bytes=image_bytes,
                sha256=digest.hexdigest(), target_mib=target_mib)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', type=Path, default=Path('.tools/pdf-load-fixtures'))
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    manifest = {'generator': 'yeobaek-pdf-load-v1', 'fixtures': []}
    for size, text in [(100, True), (300, False)]:
        name = f'{size}MiB-' + ('mixed.pdf' if text else 'scan.pdf')
        record = generate(args.out / name, size, text)
        manifest['fixtures'].append(record)
        print(json.dumps(record), flush=True)
    (args.out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
