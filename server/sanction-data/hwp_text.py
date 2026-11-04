"""HWP 5.0(한글) 문서에서 본문 텍스트만 뽑는 작은 추출기.

금감원 제재공시 첨부파일 중 2018~2020년 무렵 것은 PDF가 아니라 HWP라서 필요하다.
HWP 5.0은 OLE 복합 문서이고, BodyText/SectionN 스트림(대개 raw deflate 압축) 안의
레코드 중 HWPTAG_PARA_TEXT(태그 67)에 UTF-16LE 문단 텍스트가 들어 있다.
"""
import struct
import zlib

import olefile

HWPTAG_BEGIN = 0x10
HWPTAG_PARA_TEXT = HWPTAG_BEGIN + 51

# 문단 텍스트 안의 제어 문자: 글자 1개짜리(char)와 8글자(16바이트)짜리(inline/extended)
_CHAR_CTRLS = {0, 10, 13, 24, 25, 26, 27, 28, 29, 30, 31}
_INLINE_EXT_CTRLS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23}


def _para_text(data: bytes) -> str:
    out = []
    i = 0
    n = len(data) - 1
    while i < n:
        code = data[i] | (data[i + 1] << 8)
        if code < 32:
            if code in _INLINE_EXT_CTRLS:
                if code == 9:
                    out.append("\t")
                i += 16
                continue
            if code in (10, 13):
                out.append("\n")
            elif code == 24:
                out.append("-")
            elif code in (30, 31):
                out.append(" ")
            i += 2
            continue
        out.append(chr(code))
        i += 2
    # 한자 확장 등 UTF-16 대리 쌍을 제대로 합치고, 짝이 없는 것은 버린다.
    return "".join(out).encode("utf-16-le", "surrogatepass").decode("utf-16-le", "ignore")


def extract(path: str) -> str:
    ole = olefile.OleFileIO(path)
    try:
        header = ole.openstream("FileHeader").read()
        compressed = bool(header[36] & 1)
        sections = sorted(
            (e for e in ole.listdir() if len(e) == 2 and e[0] == "BodyText" and e[1].startswith("Section")),
            key=lambda e: int(e[1][7:] or 0),
        )
        paras = []
        for entry in sections:
            raw = ole.openstream(entry).read()
            if compressed:
                raw = zlib.decompress(raw, -15)
            pos = 0
            while pos + 4 <= len(raw):
                (hdr,) = struct.unpack_from("<I", raw, pos)
                pos += 4
                tag = hdr & 0x3FF
                size = (hdr >> 20) & 0xFFF
                if size == 0xFFF:
                    (size,) = struct.unpack_from("<I", raw, pos)
                    pos += 4
                if tag == HWPTAG_PARA_TEXT:
                    paras.append(_para_text(raw[pos:pos + size]).rstrip("\n"))
                pos += size
        return "\n".join(p for p in paras)
    finally:
        ole.close()


if __name__ == "__main__":
    import sys

    print(extract(sys.argv[1]))
