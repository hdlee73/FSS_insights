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

# (거래소 키, 표시 이름, DART 상호 검색어, 법인등록번호(숫자 13자리, 알면 그것으로 정확히 확정))
CORPS = [
    ("upbit", "업비트", ["두나무"], None),
    ("bithumb", "빗썸", ["빗썸"], "1101115307388"),
    ("coinone", "코인원", ["코인원"], None),
    ("korbit", "코빗", ["디지털엑스", "코빗"], None),
    ("gopax", "고팍스", ["스트리미", "고팍스"], None),
]
INTERIM = [("11014", "3분기"), ("11012", "반기"), ("11013", "1분기")]  # 최신 것부터

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


def parse_api_accounts(rows: list[dict], cumulative: bool = False) -> dict[str, float | None]:
    """fnlttSinglAcntAll 의 list → 항목별 당기 금액(원)."""
    out: dict[str, float | None] = {k: None for k in ACCOUNTS}
    for key, names in ACCOUNTS.items():
        for nm in names:
            for r in rows:
                if re.sub(r"\s", "", r.get("account_nm", "")) == nm and r.get("sj_div") in ("BS", "IS", "CIS"):
                    # 분·반기 손익은 3개월분(thstrm_amount)과 누적(thstrm_add_amount)이 따로 있어 누적을 쓴다.
                    v = num(r.get("thstrm_add_amount")) if cumulative and r.get("sj_div") != "BS" else None
                    if v is None:
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


def fetch_report_api(session, key, corp, year, code="11011", period="연간"):
    """정기보고서 재무제표. 연결(CFS)과 별도(OFS)가 모두 있으면 둘 다 돌려준다."""
    out = []
    for fs in ("CFS", "OFS"):
        d = get(session, "fnlttSinglAcntAll.json", crtfc_key=key, corp_code=corp, bsns_year=year,
                reprt_code=code, fs_div=fs).json()
        if d.get("status") == "000" and d.get("list"):
            v = parse_api_accounts(d["list"], cumulative=code != "11011")
            if any(x is not None for x in v.values()):
                out.append({"year": year, "period": period, "source": "정기보고서(DART)",
                            "basis": "연결" if fs == "CFS" else "별도", **v})
    return out


def norm(name: str) -> str:
    return re.sub(r"주식회사|\(주\)|㈜|\s", "", name or "")


def company_info(session, key, code):
    d = get(session, "company.json", crtfc_key=key, corp_code=code).json()
    return {"corp_code": code, "name": d.get("corp_name", ""), "jurir_no": re.sub(r"\D", "", d.get("jurir_no", "") or ""),
            "stock_code": (d.get("stock_code") or "").strip()}


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
        try:
            z = zipfile.ZipFile(io.BytesIO(get(s, "corpCode.xml", crtfc_key=key).content))
            allxml = z.read(z.namelist()[0]).decode("utf-8", "ignore")
        except Exception as e:
            log.error("DART 고유번호 목록을 받지 못해 중단: %s", e)
            return 0
        listing = [(m.group(1), m.group(2)) for m in re.finditer(
            r"<corp_code>(\d+)</corp_code>\s*<corp_name>(.*?)</corp_name>", allxml, flags=re.S)]
        for ex, name, needles, jurir in CORPS:
            cands = [(c, n) for c, n in listing if any(nd in n for nd in needles)]
            # 정확한 상호 우선, 그다음 검색어 순서
            cands.sort(key=lambda cn: (0 if norm(cn[1]) in needles else 1, min(needles.index(nd) for nd in needles if nd in cn[1])))
            log.info("%s 후보: %s", name, cands[:12])
            chosen, rows = None, []
            for code, cname in cands[:12]:
                try:
                    info = company_info(s, key, code)
                except Exception as e:
                    log.warning("%s(%s) 기업개황 실패: %s", name, code, e)
                    continue
                log.info("%s 후보 확인: %s", name, info)
                if jurir and info["jurir_no"] != jurir:
                    continue
                got = []
                for y in years:
                    try:
                        got += fetch_report_api(s, key, code, y)
                    except Exception as e:
                        log.warning("%s(%s) %s 재무 실패: %s", name, code, y, e)
                if not got:
                    try:
                        audits = fetch_audit_reports(s, key, code, years)
                    except Exception as e:
                        log.warning("%s(%s) 감사보고서 실패: %s", name, code, e)
                        audits = {}
                    got = [dict(v, period="연간") for v in audits.values()]
                if not got:
                    continue
                # 최근 분·반기(올해, 없으면 작년 것은 쓰지 않음): 연결·별도별로 가장 최근 것 하나씩
                seen = set()
                for rc, label in INTERIM:
                    try:
                        for r in fetch_report_api(s, key, code, this_year, rc, label):
                            if r["basis"] not in seen:
                                seen.add(r["basis"])
                                got.append(r)
                    except Exception as e:
                        log.warning("%s(%s) %s %s 실패: %s", name, code, this_year, label, e)
                chosen, rows = info, got
                break
            if not chosen:
                log.warning("%s: 확정된 법인 없음", name)
                res.setdefault(ex, {"name": name, "corp": "", "corp_code": "", "years": []})
                continue
            rows.sort(key=lambda r: (-r["year"], 0 if r.get("period") == "연간" else -1, 0 if r["basis"] == "연결" else 1))
            log.info("%s → %s(%s): %s", name, chosen["name"], chosen["corp_code"], [(r["year"], r["period"], r["basis"]) for r in rows])
            res[ex] = {"name": name, "corp": chosen["name"], "corp_code": chosen["corp_code"],
                       "jurir_no": chosen["jurir_no"], "listed": bool(chosen["stock_code"]), "years": rows}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"updated": datetime.now(KST).strftime("%Y-%m-%d %H:%M KST"), "exchanges": res},
                               ensure_ascii=False, separators=(",", ":")), "utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
