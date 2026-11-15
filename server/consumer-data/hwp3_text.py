"""HWP 3.0(1990년대 한글 97 형식, OLE가 아님) 본문 글을 뽑는 간이 추출기.

금감원 분쟁조정결정례 중 1999~2004년 게시글 첨부가 이 형식이다. 문서 머리(30+128+1008바이트)와 부가정보 블록 뒤가
raw deflate로 압축돼 있고, 그 안의 글자는 2바이트씩이다: ASCII는 (코드, 0), 한글은 상용조합형(johab) 코드.
문단·글자모양 구조를 모두 해석하지 않고 2바이트씩 읽어 글자만 모으는 간이 방식이라, 문단 앞에 서식 찌꺼기가 조금 붙을 수 있어 정리한다.
"""
import re
import struct
import zlib


def is_hwp3(data: bytes) -> bool:
    return data[:20] == b"HWP Document File V3"


def extract(data: bytes) -> str:
    di = data[30:158]
    compressed = struct.unpack_from("<H", di, 124)[0] != 0
    info_len = struct.unpack_from("<H", di, 126)[0]
    off = 30 + 128 + 1008
    raw = None
    for start in ([off + info_len, off] if info_len else [off]):
        if compressed:
            try:
                raw = zlib.decompressobj(-15).decompress(data[start:])
                break
            except zlib.error:
                continue
        else:
            raw = data[start:]
            break
    if raw is None:  # 압축 표시와 달리 압축되지 않은 파일
        raw = data[off + info_len:]
    out, i, n = [], 0, len(raw)
    while i + 1 < n:
        a, b = raw[i], raw[i + 1]
        if b == 0 and 0x20 <= a < 0x7F:
            out.append(chr(a)); i += 2; continue
        if b == 0 and a in (10, 13):
            out.append("\n"); i += 2; continue
        if 0x84 <= b <= 0xD3 or 0xD8 <= b <= 0xF9:
            try:
                out.append(bytes([b, a]).decode("johab")); i += 2; continue
            except UnicodeDecodeError:
                pass
        if 0x84 <= a <= 0xD3 or 0xD8 <= a <= 0xF9:
            try:
                out.append(bytes([a, b]).decode("johab")); i += 2; continue
            except UnicodeDecodeError:
                pass
        i += 1
    text = "".join(out)
    lines = []
    for ln in text.split("\n"):
        if "imquy" in ln:  # 문단 서식 표지(글자 모양 표)가 줄 앞에 붙은 것
            ln = ln.rsplit("imquy", 1)[1]
            ln = re.sub(r"^\S{0,30}?,\s?d{1,3}(?:\s?d{1,3})*\s?", "", ln)
        else:
            ln = re.sub(r"^.{0,60}?d{3,}", "", ln) if re.match(r"^.{0,60}?d{3,}", ln) else ln
        ln = ln.replace("}핷걎맯낻닳", "")
        ln = re.sub(r"^\S{0,14}?,[bd]{1,4}(?=[\s\d가-힣(\[“‘'\"])", "", ln)
        ln = re.sub(r"[\s]*,d{1,3}(?=\d{1,2}\.\s)", "\n", ln)
        ln = re.sub(r"^d(?=\S)", "", ln)
        ln = re.sub(r"^d\s+(?=[가-힣\d(])", "", ln)
        ln = re.sub(r"[^\S\n]+", " ", ln).strip()
        if not ln or (len(ln) < 40 and not re.search(r"[가-힣]", ln) and not re.search(r"\d\.", ln)):
            continue
        lines.append(ln)
    return "\n".join(l.strip() for ln in lines for l in ln.split("\n") if l.strip())
