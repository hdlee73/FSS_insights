#!/usr/bin/env python3
"""금융투자협회 종합통계 포털(FreeSIS) '회사별설정규모'(펀드 탭)에서 운용사·증권사별 펀드 순자산총액을 모은다.

공모/사모를 나누고(투자지역 전체), 최근일자·최직근 분기말·최직근 연말 세 시점을 받아
fund-aum.json 하나로 정리한다. 앱·웹은 이 파일만 받아 금융회사 주요 현황의 '펀드 순자산' 탭에 보여 준다.
요청은 시점 3개 x 공모/사모 2개 = 6건뿐이다.

사용: python3 build_fund_aum.py fund-aum.json
"""
import json
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone

BASE = "https://freesis.kofia.or.kr/meta/"
REFERER = "https://freesis.kofia.or.kr/stat/FreeSIS.do?parentDivId=MSIS40200000000000&serviceId=STATFND0200100010"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36"

# 열 번호(TMPV): 합계 14, 전일대비 16, 전년대비 17 (펀드 탭, 단위 일억원 = 억원)
COL_TOTAL = "TMPV14"
# TMPV13 = 기관전용 사모펀드(차파트너스 등 PEF 운용사 열). 합계에서 빼서 저장한다.
COL_PEF = "TMPV13"


def post(endpoint, body, tries=6):
    """FreeSIS 응답은 가끔 중간에 끊겨 오므로 온전한 JSON이 올 때까지 다시 받는다."""
    last = ""
    for i in range(tries):
        p = subprocess.run(
            ["curl", "-sS", "--compressed", "-m", "90", "-A", UA,
             "-H", "Content-Type: application/json; charset=UTF-8", "-e", REFERER,
             "-d", json.dumps(body), BASE + endpoint],
            capture_output=True)
        try:
            return json.loads(p.stdout.decode("utf-8", "replace"), strict=False)
        except Exception as e:  # noqa: BLE001
            last = f"{e} (받은 {len(p.stdout)}바이트) {p.stderr[:120]!r}"
            time.sleep(2 + 2 * i)
    raise RuntimeError(f"FreeSIS {endpoint} 응답을 읽지 못했습니다: {last}")


def latest_dates():
    d = post("getSrvData.do", {"dmSearchData": {
        "strSvrId": "STATFND0200100130", "strDivId": "MSIS40000000000000",
        "app_peron_yn": "Y", "language_gb": "KOR", "strGetCode": "Y"}})
    m = {r.get("TMPV1"): r.get("TMPV2") for r in d.get("dsLatestDate", [])}
    out = {"recent": m.get("RD"), "quarter": m.get("RQ"), "year": m.get("RY")}
    if not all(out.values()):
        raise RuntimeError(f"기준일을 받지 못했습니다: {m}")
    return out


def fetch(date, kind):
    """kind: 1 공모, 2 사모. 반환: {회사명: 순자산총액(억원)}"""
    d = post("getMetaDataList.do", {"dmSearch": {
        "tmpV40": "100000000", "tmpV41": "1", "tmpV34": date, "tmpV6": "2",
        "tmpV4": "", "tmpV5": "", "tmpV7": str(kind), "tmpV20": "Y",
        "OBJ_NM": "STATFND0200100010BO"}})
    rows = d.get("ds1") or []
    out = {}
    for r in rows:
        name = (r.get("TMPV1") or "").strip()
        # 표 중간의 소계·합계 줄은 회사가 아니다.
        if not name or any(w in name for w in ("소계", "합계", "총계", "(기타)")) or name == "계":
            continue
        v = r.get(COL_TOTAL)
        v = v if isinstance(v, (int, float)) else 0
        pef = r.get(COL_PEF)
        pef = pef if isinstance(pef, (int, float)) else 0
        out[name] = max(int(v) - int(pef), 0)
    return out


def main(path):
    dates = latest_dates()
    print("기준일", dates)
    series = {}  # 이름 -> {"pub": [r,q,y], "pri": [r,q,y]}
    for idx, key in enumerate(("recent", "quarter", "year")):
        for kind, tag in ((1, "pub"), (2, "pri")):
            data = fetch(dates[key], kind)
            print(key, dates[key], tag, len(data), "곳, 합", sum(data.values()))
            if len(data) < 30:
                raise RuntimeError(f"{key}/{tag} 자료가 너무 적습니다({len(data)}곳)")
            for name, v in data.items():
                s = series.setdefault(name, {"pub": [0, 0, 0], "pri": [0, 0, 0]})
                s[tag][idx] = v
    companies = [
        {"n": n, "pub": s["pub"], "pri": s["pri"]}
        for n, s in sorted(series.items()) if any(s["pub"]) or any(s["pri"])
    ]
    if len(companies) < 100:
        raise RuntimeError(f"회사가 너무 적습니다({len(companies)}곳)")
    kst = timezone(timedelta(hours=9))
    out = {
        "generatedAt": datetime.now(kst).strftime("%Y-%m-%dT%H:%M:%S+09:00"),
        "unit": "억원",
        "asOf": dates,
        "source": "금융투자협회 종합통계 포털 '회사별설정규모'(펀드, 순자산총액, 투자지역 전체, 기관전용 사모펀드 제외)",
        "companies": companies,
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print("저장", path, len(companies), "곳")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "fund-aum.json")
