#!/usr/bin/env python3
"""Real image bytes, not padding. Large archives stay outside Git under .tools/."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import zipfile
from PIL import Image, ImageDraw

MIB = 1024 * 1024


def checksum(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(MIB), b''): digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out', type=Path, default=Path('.tools/image-load-fixtures'))
    parser.add_argument('--seven', type=Path, default=Path('.tools/image-deps/7zip/7zz'))
    parser.add_argument('--rar', type=Path, default=Path('.tools/image-deps/rar/rar/rar'))
    parser.add_argument('--zip-only', action='store_true', help='Generate ZIP/dimension fixtures without external 7z/RAR encoders; report other formats as not generated')
    args = parser.parse_args()
    out = args.out.resolve(); out.mkdir(parents=True, exist_ok=True)
    pool = out / 'pages'; pool.mkdir(exist_ok=True)
    records = []
    def record(path, count, **extra):
        row = dict(file=path.name, bytes=path.stat().st_size, sha256=checksum(path), pages=count, **extra)
        records.append(row); print(json.dumps(row), flush=True)
    pool_size = 0
    for target in [100, 300, 1024]:
        path = out / f'{target}MiB.zip'
        if not path.exists():
            total = 0; index = 0
            with zipfile.ZipFile(path, 'w', zipfile.ZIP_STORED, allowZip64=True) as archive:
                while total < target * MIB:
                    pixels = hashlib.shake_256(f'yeobaek-image-load-v1-{index}'.encode()).digest(1536 * 2048 * 3)
                    image = Image.frombytes('RGB', (1536, 2048), pixels)
                    draw = ImageDraw.Draw(image); draw.rectangle((30, 30, 1500, 150), fill='white')
                    draw.text((55, 65), f'Original image fixture / page {index + 1}', fill='black', font_size=36)
                    data = io.BytesIO(); image.save(data, format='JPEG', quality=90, subsampling=0)
                    jpeg = data.getvalue(); archive.writestr(f'volume1/{index + 1:05d}.jpg', jpeg)
                    if target == 300: (pool / f'{index + 1:05d}.jpg').write_bytes(jpeg)
                    total += len(jpeg); index += 1
        with zipfile.ZipFile(path) as archive: count = len(archive.infolist())
        if target == 300:
            pool_size = count
            if not list(pool.glob('*.jpg')):
                with zipfile.ZipFile(path) as archive:
                    for entry in archive.infolist(): (pool / Path(entry.filename).name).write_bytes(archive.read(entry))
        record(path, count, target_mib=target)
    for solid in ([] if args.zip_only else [False, True]):
        label = 'solid' if solid else 'normal'
        for extension in ['7z', 'rar']:
            path = out / f'300MiB-{label}.{extension}'
            if not path.exists():
                command = ([str(args.seven.resolve()), 'a', '-t7z', '-mx=1', '-mmt=2', '-ms=' + ('on' if solid else 'off'), str(path), '.'] if extension == '7z'
                    else [str(args.rar.resolve()), 'a', '-ma5', '-m1', '-mt2', '-s' if solid else '-s-', '-ep1', str(path), '.'])
                subprocess.run(command, cwd=pool, check=True, stdout=subprocess.DEVNULL)
            record(path, pool_size, solid=solid, target_mib=300)
    edge = out / 'dimensions.zip'
    if not edge.exists():
        with zipfile.ZipFile(edge, 'w', zipfile.ZIP_DEFLATED) as archive:
            for name, w, h in [('1-long.png', 1440, 40000), ('2-large.jpg', 12000, 12000)]:
                image = Image.new('RGB', (w, h), '#faf7f0'); draw = ImageDraw.Draw(image)
                for y in range(0, h, 800):
                    draw.rectangle((0, y, w, y + 150), fill=(20 + y % 200, 100, 80)); draw.text((40, y + 30), f'Y = {y}', fill='white', font_size=50)
                data = io.BytesIO(); image.save(data, format='PNG' if name.endswith('png') else 'JPEG')
                archive.writestr(name, data.getvalue())
    record(edge, 2)
    (out / 'manifest.json').write_text(json.dumps(dict(generator='yeobaek-image-load-v1', fixtures=records,
        not_generated=['300MiB-normal.7z', '300MiB-solid.7z', '300MiB-normal.rar', '300MiB-solid.rar'] if args.zip_only else []), indent=2) + '\n')


if __name__ == '__main__': main()
