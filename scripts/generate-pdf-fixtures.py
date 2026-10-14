#!/usr/bin/env python3
"""Deterministic, original PDF fixtures; no downloaded books or document dependencies."""
from pathlib import Path
import argparse
import zlib
import hashlib
import struct


def rc4(key, data):
    state = list(range(256)); j = 0
    for i in range(256):
        j = (j + state[i] + key[i % len(key)]) % 256
        state[i], state[j] = state[j], state[i]
    i = j = 0; out = bytearray()
    for byte in data:
        i = (i + 1) % 256; j = (j + state[i]) % 256
        state[i], state[j] = state[j], state[i]
        out.append(byte ^ state[(state[i] + state[j]) % 256])
    return bytes(out)


def pdf(path, pages, scanned=False, protected=False):
    objects = [b'', b'', b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>']
    pixels = bytes([220, 40, 20, 10, 120, 220, 30, 180, 60, 240, 210, 20])
    image = zlib.compress(pixels)
    objects.append(b'<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length ' + str(len(image)).encode() + b' >>\nstream\n' + image + b'\nendstream')
    page_ids = []
    for i in range(pages):
        rotate = 90 if i == 1 else 0
        text = f'BT /F1 24 Tf 40 720 Td (Yeobaek PDF page {i + 1}) Tj 0 -40 Td (Searchable story and selection.) Tj ET\n'
        drawing = 'q 200 0 0 160 40 380 cm /Im1 Do Q\n0.1 0.5 0.8 rg 40 300 200 40 re f\n'
        content = (drawing if scanned else text + drawing).encode()
        page_id = len(objects) + 1
        page_ids.append(page_id)
        objects.append(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Rotate {rotate} /Resources << /Font << /F1 3 0 R >> /XObject << /Im1 4 0 R >> >> /Contents {page_id + 1} 0 R >>'.encode())
        objects.append(f'<< /Length {len(content)} >>\nstream\n'.encode() + content + b'endstream')
    objects[0] = b'<< /Type /Catalog /Pages 2 0 R >>'
    objects[1] = f'<< /Type /Pages /Count {pages} /Kids ['.encode() + b' '.join(f'{i} 0 R'.encode() for i in page_ids) + b'] >>'
    trailer = b''
    if protected:
        padding = bytes.fromhex('28bf4e5e4e758a4164004e56fffa01082e2e00b6d0683e802f0ca9fe6453697a')
        padded = (b'secret' + padding)[:32]
        owner = rc4(hashlib.md5(padded).digest()[:5], padded)
        doc_id = b'yeobaek-fixture1'
        key = hashlib.md5(padded + owner + struct.pack('<i', -4) + doc_id).digest()[:5]
        user = rc4(key, padding)
        for number, obj in enumerate(objects, 1):
            if b'\nstream\n' in obj:
                header, stream = obj.split(b'\nstream\n', 1)
                data, end = stream.rsplit(b'endstream', 1)
                object_key = hashlib.md5(key + number.to_bytes(3, 'little') + b'\0\0').digest()[:10]
                objects[number - 1] = header + b'\nstream\n' + rc4(object_key, data) + b'endstream' + end
        objects.append(f'<< /Filter /Standard /V 1 /R 2 /Length 40 /P -4 /O <{owner.hex()}> /U <{user.hex()}> >>'.encode())
        trailer = f' /Encrypt {len(objects)} 0 R /ID [<{doc_id.hex()}> <{doc_id.hex()}>]'.encode()
    out = bytearray(b'%PDF-1.7\n%\xe2\xe3\xcf\xd3\n')
    offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(out)); out += f'{i} 0 obj\n'.encode() + obj + b'\nendobj\n'
    xref = len(out)
    out += f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode()
    out += b''.join(f'{offset:010d} 00000 n \n'.encode() for offset in offsets[1:])
    out += f'trailer\n<< /Size {len(offsets)} /Root 1 0 R'.encode() + trailer + f' >>\nstartxref\n{xref}\n%%EOF\n'.encode()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(out)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', type=Path, default=Path('app/src/androidTest/assets/pdf'))
    args = parser.parse_args()
    pdf(args.out / 'mixed.pdf', 3)
    pdf(args.out / 'scanned.pdf', 2, scanned=True)
    pdf(args.out / 'large.pdf', 500)
    pdf(args.out / 'locked.pdf', 1, protected=True)
    (args.out / 'broken.pdf').write_bytes(b'%PDF-1.7\nnot a document')
