#!/usr/bin/env python3
"""Deterministic, synthetic books. No external books or personal documents."""
import argparse, hashlib, json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--output', default='.tools/fixtures/Yeobaek')
parser.add_argument('--large', action='store_true')
args = parser.parse_args()
root = Path(args.output)
root.mkdir(parents=True, exist_ok=True)
manifest = []

def save(name, data, charset='UTF-8', expected='readable'):
    file = root / name
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_bytes(data)
    manifest.append(dict(name=name, bytes=len(data), charset=charset, sha256=hashlib.sha256(data).hexdigest(), expected=expected, seed=1))

story = ('비가 그친 오후\n\n창밖에서 다시 바람이 불었다. 젖은 잎이 서로 닿는 소리를 들으며, 나는 다음 문장으로 천천히 시선을 옮겼다.\n\n'
         '비가 그친 뒤의 골목은 평소보다 조금 넓어 보였다. 가게 앞에 놓인 작은 화분들에는 아직 물방울이 매달려 있었다. 나는 서두르지 않고 그 사이를 걸었다.\n\n'
         '익숙한 길에도 처음 보는 장면이 있었다. 닫힌 창문에 비친 하늘, 누군가 반듯하게 접어 둔 우산, 빵집 문틈으로 흘러나오는 따뜻한 냄새 같은 것들.\n\n'
         '“오늘은 조금 늦어도 괜찮겠네.”\n\n누구에게 하는 말인지도 모른 채 작게 중얼거렸다. 주머니 속 휴대전화는 조용했고, 다음 모퉁이까지는 아직 걸어갈 시간이 남아 있었다.\n\n')
save('비가 그친 오후.txt', (story * 100).encode())
save('숲으로 가는 길.txt', ('숲으로 가는 길\n\n' + story * 10).encode())
save('여름의 마지막 편지.txt', ('여름의 마지막 편지\n\n' + story * 10).encode())
save('Recovery/Original.txt', (story * 100).encode())
save('Recovery/Replacement.txt', '완전히 달라진 본문입니다.\n새로운 문장만 남았습니다.\n'.encode())
save('완결/한글 CP949.txt', ('똠 뷁 한글 가나다\r\n' * 300).encode('cp949'), 'CP949')
save('메모/UTF16LE.txt', b'\xff\xfe' + story.encode('utf-16-le'), 'UTF-16LE')
save('메모/UTF16BE.txt', b'\xfe\xff' + story.encode('utf-16-be'), 'UTF-16BE')
save('메모/빈 파일.txt', b'')
save('메모/공백.txt', b' \t\n\n')
save('메모/손상 UTF8.txt', story.encode() + b'\xff\xff\x00\x00' * 2000, 'invalid', 'error')
save('메모/미지원.pdf', b'%PDF-1.7\nnot a supported book', 'binary', 'unsupported')
save('메모/아주 긴 파일 이름도 표시할 수 있어야 합니다 그리고 정보 화면에서 전체 이름을 확인해 주세요.txt', story.encode())
(root/'빈 폴더').mkdir(exist_ok=True)

if args.large:
    for mib in [1,10,50,100,101]:
        file = root / f'성능/{mib:03}MiB.txt'
        file.parent.mkdir(exist_ok=True)
        unit = story.encode()
        target = mib * 1024 * 1024
        digest = hashlib.sha256()
        with file.open('wb') as out:
            for _ in range(target // len(unit)):
                out.write(unit); digest.update(unit)
            tail = b' ' * (target % len(unit)); out.write(tail); digest.update(tail)
        manifest.append(dict(name=str(file.relative_to(root)), bytes=target, charset='UTF-8', sha256=digest.hexdigest(), expected='limit' if mib > 100 else 'stress' if mib == 100 else 'readable', seed=1))
    save('성능/줄바꿈 없는 1MiB.txt', ('가나다라마바사 아자차카타파하 '.encode()*30000)[:1048575].decode('utf-8', errors='ignore').encode())
    save('성능/빈 줄 수십만 개.txt', ('\n가\n\n' * 200000).encode())
    save('성능/결합문자.txt', ('한글 e\u0301 👩‍👩‍👧‍👦 🇰🇷\n'*30000).encode())
    for number in range(1000):
        save(f'천개 폴더/책 {number:04}.txt', f'책 {number}\n검사용 본문입니다.\n'.encode())

(root.parent/'fixtures.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
print(f'{len(manifest)} fixtures: {root}')
