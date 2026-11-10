"""거래소별 재무·손익 현황(DART). 정기보고서(사업보고서) 제출 법인은 재무제표 API, 그 외는 감사보고서 본문(document.xml)에서 읽는다.

결과: <out>/data/exchange_fin.json  (실패한 거래소는 비워 두고 기존 파일 값을 유지)
키: 환경변수 DART_API_KEY (GitHub Secret). 키가 없거나 실패하면 아무것도 바꾸지 않고 정상 종료한다.
"""
from __future__ import annotations

import argparse
import io
import json
import logging
import os
import re
import sys
import zipfile
from datetime import datetime, timezone, timedelta
from pathlib import Path

import requests

log = logging.getLogger("exfin")
API = "https://opendart.fss.or.kr/api"
KST = timezone(timedelta(hours=9))

# (거래소 키, 표시 이름, 운영 법인, DART 고유번호)
CORPS = [
    ("upbit", "업비트", "두나무", "01310241"),
    ("bithumb", "빗썸", "빗썸코리아", "01214044"),
    ("coinone", "코인원", "코인원", "01263378"),
    ("korbit", "코빗", "코빗", "01221743"),
    ("gopax", "고팍스", "스트리미", "01307618"),
]

ACCOUNTS = {  # 항목 키 → 계정명 후보(앞쪽 우선)
    "assets": ["자산총계"],
    "liabilities": ["부채총계"],
    "equity": ["자본총계"],
    "revenue": ["영업수익", "매출액", "수익(매출액)", "영업수익합계"],
    "op_income": ["영업이익", "영업이익(손실)", "영업손익"],
    "net_income": ["당기순이익", "당기순이익(손실)", "당기순손익", "연결당기순이익"],
}


def num(s) -> float | None:
    if s is None:
        return None
    t = str(s).strip().replace(",", "").replace(" ", "")
    neg = t.startswith("(") and t.endswith(")") or t.startswith("△") or t.startswith("▲")
    t = t.strip("()△▲")
    if t.startswith("-"):
        neg, t = True, t[1:]
    if not re.fullmatch(r"\d+(\.\d+)?", t):
        return None
    v = float(t)
    return -v if neg else v


def parse_api_accounts(rows: list[dict]) -> dict[str, float | None]:
    """fnlttSinglAcntAll 의 list → 항목별 당기 금액(원)."""
    out: dict[str, float | None] = {k: None for k in ACCOUNTS}
    for key, names in ACCOUNTS.items():
        for nm in names:
            for r in rows:
                if re.sub(r"\s", "", r.get("account_nm", "")) == nm and r.get("sj_div") in ("BS", "IS", "CIS"):
                    v = num(r.get("thstrm_amount"))
                    if v is not None:
                        out[key] = v
                        break
            if out[key] is not None:
                break
    return out


def _cells(tr: str) -> list[str]:
    return [re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", c)).strip()
            for c in re.findall(r"<T[DEH][^>]*>(.*?)</T[DEH]>", tr, flags=re.S | re.I)]


def parse_audit_xml(xml: str) -> dict:
    """감사보고서 XML 에서 재무상태표·손익계산서의 첫 금액 열(당기)을 읽는다. 단위는 '(단위 : 백만원)' 등에서 추정."""
    unit = 1.0
    m = re.search(r"단위\s*[:：]?\s*(원|천원|백만원|억원)", re.sub(r"<[^>]+>", "", xml))
    if m:
        unit = {"원": 1.0, "천원": 1e3, "백만원": 1e6, "억원": 1e8}[m.group(1)]
    vals: dict[str, float | None] = {k: None for k in ACCOUNTS}
    for tr in re.findall(r"<TR[^>]*>(.*?)</TR>", xml, flags=re.S | re.I):
        cells = [c for c in _cells(tr) if c != ""]
        if len(cells) < 2:
            continue
        label = re.sub(r"^[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ\d\.\s]+", "", cells[0])
        label = re.sub(r"\s", "", label)
        for key, names in ACCOUNTS.items():
            if vals[key] is None and label in names:
                for c in cells[1:]:
                    n = num(c)
                    if n is not None:
                        vals[key] = n * unit
                        break
    return {"unit": unit, **vals}


def get(session, path, **params):
    r = session.get(f"{API}/{path}", params=params, timeout=40)
    r.raise_for_status()
    return r


def fetch_year_api(session, key, corp, year):
    """사업보고서 재무제표. 연결(CFS)과 별도(OFS)가 모두 있으면 둘 다 돌려준다."""
    out = []
    for fs in ("CFS", "OFS"):
        d = get(session, "fnlttSinglAcntAll.json", crtfc_key=key, corp_code=corp, bsns_year=year,
                reprt_code="11011", fs_div=fs).json()
        if d.get("status") == "000" and d.get("list"):
            v = parse_api_accounts(d["list"])
            if any(x is not None for x in v.values()):
                out.append({"year": year, "source": "사업보고서(DART)", "basis": "연결" if fs == "CFS" else "별도", **v})
    return out


def find_corps(session, key, needle):
    """DART 전체 고유번호 목록에서 이름에 needle 이 들어간 법인(정확한 법인을 찾기 위한 보조)."""
    z = zipfile.ZipFile(io.BytesIO(get(session, "corpCode.xml", crtfc_key=key).content))
    xml = z.read(z.namelist()[0]).decode("utf-8", "ignore")
    out = []
    for m in re.finditer(r"<list>(.*?)</list>", xml, flags=re.S):
        blk = m.group(1)
        code = re.search(r"<corp_code>(\d+)</corp_code>", blk)
        name = re.search(r"<corp_name>(.*?)</corp_name>", blk)
        if code and name and needle in name.group(1):
            out.append((code.group(1), name.group(1)))
    return out


def fetch_audit_reports(session, key, corp, years):
    d = get(session, "list.json", crtfc_key=key, corp_code=corp, bgn_de=f"{years[-1]}0101",
            end_de=f"{years[0] + 1}1231", pblntf_ty="F", page_count=100).json()
    out = {}
    for it in d.get("list") or []:
        nm = it.get("report_nm", "")
        if "감사보고서" not in nm:
            continue
        m = re.search(r"\((\d{4})\.\d{2}\)", nm)
        yr = int(m.group(1)) if m else None
        if yr is None or yr in out:
            continue
        try:
            z = zipfile.ZipFile(io.BytesIO(get(session, "document.xml", crtfc_key=key, rcept_no=it["rcept_no"]).content))
            xml = "".join(z.read(n).decode("utf-8", "ignore") for n in z.namelist() if n.lower().endswith((".xml", ".html")))
        except Exception as e:  # 오류 응답(JSON/XML status)이거나 zip 아님
            log.warning("%s 감사보고서 열기 실패: %s", corp, e)
            continue
        v = parse_audit_xml(xml)
        v.pop("unit", None)
        if any(x is not None for x in v.values()):
            out[yr] = {"year": yr, "source": "감사보고서(DART)", "basis": "별도", "rcept_no": it["rcept_no"], **v}
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    key = os.environ.get("DART_API_KEY")
    path = args.out / "data" / "exchange_fin.json"
    old = json.loads(path.read_text("utf-8")) if path.exists() else {"exchanges": {}}
    if not key:
        log.warning("DART_API_KEY 없음 — 거래소 재무 갱신 건너뜀")
        return 0
    this_year = datetime.now(KST).year
    years = [this_year - 1, this_year - 2, this_year - 3]
    res = dict(old.get("exchanges") or {})
    with requests.Session() as s:
        for ex, name, corp_name, corp in CORPS:
            cands = [corp]
            try:  # 같은 이름 계열 법인도 후보로 시도(고유번호가 맞지 않을 때 대비)
                found = find_corps(s, key, name)
                log.info("%s 후보 법인: %s", name, found)
                cands += [c for c, _ in found if c != corp]
            except Exception as e:
                log.warning("%s 고유번호 목록 실패: %s", name, e)
            rows, used = [], corp
            for cc in cands[:6]:
                try:
                    audits = fetch_audit_reports(s, key, cc, years)
                except Exception as e:
                    log.warning("%s(%s) 감사보고서 목록 실패: %s", name, cc, e)
                    audits = {}
                got = []
                for y in years:
                    try:
                        r = fetch_year_api(s, key, cc, y)
                    except Exception as e:
                        log.warning("%s(%s) %s 재무 실패: %s", name, cc, y, e)
                        r = []
                    if not r and y in audits:
                        r = [audits[y]]
                    got += r
                log.info("%s(%s): %s", name, cc, [(r["year"], r["basis"]) for r in got])
                if got:
                    rows, used = got, cc
                    break
            rows.sort(key=lambda r: (-r["year"], 0 if r["basis"] == "연결" else 1))
            res[ex] = {"name": name, "corp": corp_name, "corp_code": used, "years": rows}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"updated": datetime.now(KST).strftime("%Y-%m-%d %H:%M KST"), "exchanges": res},
                               ensure_ascii=False, separators=(",", ":")), "utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
