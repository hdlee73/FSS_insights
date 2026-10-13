#!/usr/bin/env python3
"""OpenDART 고유번호(corpCode.xml)에서 금융회사만 골라 dart-corps.json을 만든다.

배포할 때(Deploy news proxy 워크플로) DART_API_KEY가 있으면 실행된다.
결과는 [[회사이름, corp_code, 종목코드], ...] 목록이며 서버(worker.js)가 FISIS 회사 이름과 맞춰 본다.
키가 없거나 내려받기에 실패하면 기존 파일을 그대로 둔다(서버는 DART 없이도 동작한다).
"""
import io
import json
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dart-corps.json")
KEYWORDS = re.compile(
    "은행|증권|자산운용|투자자문|투자일임|생명|화재|손해|보험|저축은행|카드|캐피탈|파이낸셜|금융|에셋|신탁|리스|인베스트먼트|선물|투자운용|운용"
)


def load_xml(path=None):
    if path:
        return open(path, "rb").read()
    key = os.environ.get("DART_API_KEY", "").strip()
    if not key:
        sys.exit("DART_API_KEY가 없어 건너뜁니다.")
    with urllib.request.urlopen(f"https://opendart.fss.or.kr/api/corpCode.xml?crtfc_key={key}", timeout=120) as r:
        data = r.read()
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            return z.read(z.namelist()[0])
    except zipfile.BadZipFile:
        sys.exit("DART 응답이 zip이 아닙니다(키 오류 등). 기존 파일을 그대로 둡니다.")


def build(xml_bytes):
    root = ET.fromstring(xml_bytes)
    rows = []
    for item in root.iter("list"):
        name = (item.findtext("corp_name") or "").strip()
        code = (item.findtext("corp_code") or "").strip()
        stock = (item.findtext("stock_code") or "").strip()
        if name and code and KEYWORDS.search(name):
            rows.append([name, code, stock])
    rows.sort(key=lambda r: r[0])
    return rows


if __name__ == "__main__":
    rows = build(load_xml(sys.argv[1] if len(sys.argv) > 1 else None))
    if not rows:
        sys.exit("금융회사를 찾지 못해 기존 파일을 그대로 둡니다.")
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, separators=(",", ":"))
    print(f"DART 금융회사 {len(rows)}곳을 dart-corps.json에 저장했습니다.")
