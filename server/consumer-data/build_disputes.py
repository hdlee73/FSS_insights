#!/usr/bin/env python3
"""금융감독원 분쟁조정 자료를 모아 disputes.json 하나로 만든다.

수집 대상(둘 다 공개 게시판, 로그인 불필요)
  1. 분쟁조정결정례  https://www.fss.or.kr/fss/bbs/B0000390/list.do?menuNo=201193   (1999년~, 800여 건)
     게시글마다 '조정결정서' 첨부(HWP 등)가 있고, 여기서 안건명·주문·신청취지·판단 요약을 뽑는다.
  2. 분쟁조정사례    https://www.fss.or.kr/fss/job/fncCnflCase/list.do?menuNo=201195   (200여 건)
     첨부(HWP)에 민원내용·쟁점·처리결과·소비자 유의사항이 정리돼 있다.

새 글만 첨부를 내려받도록 이전 결과(--prev)를 재사용한다. 첨부를 못 읽은 글도 목록 정보(제목·권역·유형·날짜)와
원문 링크는 남긴다. 사건 당사자는 원문에서 이미 가명(○○○)으로 처리돼 있다.

사용: python3 build_disputes.py disputes.json [--prev prev.json] [--cache DIR] [--workers 4] [--limit N]
"""
import argparse
import concurrent.futures as cf
import datetime as dt
import html
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "sanction-data"))
import extract_text  # noqa: E402
sys.path.insert(0, HERE)
import hwp3_text  # noqa: E402

BASE = "https://www.fss.or.kr"
UA = {"User-Agent": "Mozilla/5.0 (compatible; fss-insights-data/1.0)"}
DEC_LIST = BASE + "/fss/bbs/B0000390/list.do?menuNo=201193&pageIndex={p}"
DEC_VIEW = BASE + "/fss/bbs/B0000390/view.do?nttId={id}&menuNo=201193"
CASE_LIST = BASE + "/fss/job/fncCnflCase/list.do?menuNo=201195&pageIndex={p}"
CASE_VIEW = BASE + "/fss/job/fncCnflCase/view.do?caseSlno={id}&menuNo=201195"
SOURCE = "금융감독원 > 민원·신고 > 분쟁조정정보(분쟁조정결정례·분쟁조정사례)"


def fetch(url, tries=5, binary=False):
    err = None
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                b = r.read()
            return b if binary else b.decode("utf-8", "replace")
        except Exception as e:  # noqa: BLE001
            err = e
            time.sleep(1.5 * (i + 1))
    raise err


def txt(s):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", s or ""))).strip()


def files_in(row_html):
    out = []
    for m in re.finditer(r'<a href="(/fss/cmmn/file/fileDown\.do[^"]*)"[^>]*>.*?<span class="name">(.*?)</span>', row_html, re.S):
        out.append((html.unescape(m.group(1)), txt(m.group(2))))
    return out


def list_decisions(maxpages=200):
    rows, seen = [], set()
    for p in range(1, maxpages + 1):
        t = fetch(DEC_LIST.format(p=p))
        got = 0
        for tr in re.findall(r"<tr>(.*?)</tr>", t, re.S):
            m = re.search(r'href="[^"]*nttId=(\d+)[^"]*"[^>]*>(.*?)</a>', tr, re.S)
            if not m or m.group(1) in seen:
                continue
            tds = re.findall(r"<td[^>]*>(.*?)</td>", tr, re.S)
            sector = txt(tds[1]) if len(tds) > 1 else ""
            typ = txt(tds[2]) if len(tds) > 2 else ""
            date = re.search(r"\d{4}-\d{2}-\d{2}", tr)
            seen.add(m.group(1))
            got += 1
            rows.append({
                "id": "dec-" + m.group(1), "kind": "분쟁조정결정례", "_nid": m.group(1),
                "title": txt(m.group(2)), "sector": sector, "type": typ,
                "date": date.group(0) if date else "", "files": files_in(tr),
                "url": DEC_VIEW.format(id=m.group(1)),
            })
        if not got:
            break
    return rows


def list_cases(maxpages=100):
    rows, seen = [], set()
    for p in range(1, maxpages + 1):
        t = fetch(CASE_LIST.format(p=p))
        got = 0
        for tr in re.findall(r"<tr>(.*?)</tr>", t, re.S):
            m = re.search(r'caseSlno=(\d+)[^"]*"[^>]*>(.*?)</a>', tr, re.S)
            if not m or m.group(1) in seen:
                continue
            tds = re.findall(r"<td[^>]*>(.*?)</td>", tr, re.S)
            date = re.search(r"\d{4}-\d{2}-\d{2}", tr)
            seen.add(m.group(1))
            got += 1
            rows.append({
                "id": "case-" + m.group(1), "kind": "분쟁조정사례", "_nid": m.group(1),
                "title": txt(m.group(2)), "sector": txt(tds[1]) if len(tds) > 1 else "",
                "type": txt(tds[2]) if len(tds) > 2 else "", "date": date.group(0) if date else "",
                "files": files_in(tr), "url": CASE_VIEW.format(id=m.group(1)),
            })
        if not got:
            break
    return rows


# ---------------------------------------------------------------- 본문 해석

def clean(t):
    t = t.replace("\r", "")
    t = re.sub(r"[\u0000-\u0008\u000b\u000c\u000e-\u001f]", "", t)
    t = re.sub(r"[ \t 　]+", " ", t)
    return t


def sec_between(t, start_pat, end_pats):
    m = re.search(start_pat, t)
    if not m:
        return ""
    rest = t[m.end():]
    end = len(rest)
    for ep in end_pats:
        e = re.search(ep, rest)
        if e:
            end = min(end, e.start())
    return rest[:end].strip()


def shorten(s, n):
    s = re.sub(r"\s+", " ", s or "").strip()
    if len(s) <= n:
        return s
    cut = s[:n]
    k = max(cut.rfind("다. "), cut.rfind("함. "), cut.rfind(". "))
    return (cut[: k + 1] if k > n * 0.6 else cut).rstrip() + "…"


def to_int(s):
    try:
        return int(re.sub(r"[^0-9]", "", s))
    except ValueError:
        return None


def money_in(s):
    """'금 51,649,425원'·'5천만원' 같은 첫 금액(원)."""
    m = re.search(r"(\d[\d,]*)\s*원", s or "")
    if m:
        return to_int(m.group(1))
    m = re.search(r"(\d[\d,.]*)\s*(억|천만|백만|만)\s*원", s or "")
    return None


def classify_order(order, claim, title=""):
    """주문·신청취지로 결과를 분류한다: 인용 / 일부인용 / 기각 / 합의권고 / 기타. 인정 비율(%)도 구할 수 있으면 같이."""
    o = re.sub(r"\s+", " ", order or "")
    c = re.sub(r"\s+", " ", claim or "")
    if not o:
        return "", None
    if re.search(r"각하", o) and not re.search(r"지급|반환|배상", o):
        return "각하", None
    if re.search(r"(신청|청구)[^.]{0,30}기각|기각한다|기각함", o) and not re.search(r"(지급|반환|배상|인정|확인)", re.sub(r"(신청|청구)[^.]{0,30}기각", "", o)):
        return "기각", 0
    pay = [to_int(x) for x in re.findall(r"금?\s*(\d[\d,]{3,})\s*원", o)]
    pay = [x for x in pay if x]
    asked = [to_int(x) for x in re.findall(r"(\d[\d,]{3,})\s*원", c)]
    asked = [x for x in asked if x]
    if re.search(r"지급한다|지급하여야|지급하라|반환한다|배상한다|지급할 것|지급하고", o) or pay:
        if pay and asked:
            ratio = round(min(100, pay[0] / asked[0] * 100)) if asked[0] else None
            if ratio is not None:
                return ("인용" if ratio >= 97 else "일부인용"), ratio
        if re.search(r"(나머지|그 밖의|초과).{0,20}(기각|청구를 기각)|기각한다", o):
            return "일부인용", None
        return "인용", None
    if re.search(r"기각", o):
        return "기각", 0
    if re.search(r"확인한다|존재하지", o):
        return "인용", None
    return "기타", None


def spaced(w):
    return r"\s*".join(re.escape(c) for c in w)


def head(*names):
    """'3. 주 문'·'주문'·'가. 판단' 같은 제목 줄 패턴(앞에 번호·가나다·괄호번호 허용)."""
    alt = "|".join(spaced(n) for n in names)
    return r"\n[ \t]*(?:\d{1,2}\s*[.)]|[가-하]\s*[.)]|\(\d{1,2}\)|[◆■□○▣◎])?[ \t]*(?:" + alt + r")[ \t]*(?:[:：][ \t]*)?\n"


def parse_decision(text, row):
    t = "\n" + clean(text)
    out = {}
    m = re.search(r"조정번호\s*[:：]\s*(제?\s*[0-9]{4}\s*-\s*\d+\s*호)", t)
    if m:
        out["no"] = re.sub(r"\s+", "", m.group(1))
    m = re.search(r"(?:조정|결정)일자\s*[:：]\s*([0-9]{4})\.\s*(\d{1,2})\.\s*(\d{1,2})", t)
    if m:
        out["decidedAt"] = f"{m.group(1)}-{int(m.group(2)):02d}-{int(m.group(3)):02d}"
    if re.search(r"분쟁\s*개요", t) and not re.search(head("주문"), t):
        return parse_old(t, out)
    order = sec_between(t, head("주문"), [head("신청취지", "신청원인", "이유", "신청 내용")])
    claim = sec_between(t, head("신청취지"), [head("이유", "신청원인")])
    result, ratio = classify_order(order, claim, row["title"])
    if order:
        out["order"] = shorten(order, 220)
    if claim:
        out["claim"] = shorten(claim, 200)
    body = sec_between(t, head("이유"), [])
    facts = sec_between(body, head("기초 사실", "사실관계", "기초사실", "사건 개요", "사실 관계"), [r"\n[ \t]*(?:\d\s*[.)]|[나다]\s*[.)])[ \t]*(?:당사자|쟁점|관련|위원회|판단)"])
    judge = ""
    jh = [m for m in re.finditer(r"\n[ \t]*(?:\d{1,2}\s*[.)]|[가-하]\s*[.)]|\(\d{1,2}\)|[◆■□○▣◎])[ \t]*[^\n]{0,16}(?:판단|검토)[^\n]{0,10}\n", body)]
    if jh:
        judge = body[jh[-1].end():]
    concl = ""
    mc = list(re.finditer(head("결론"), body))
    if mc:
        concl = body[mc[-1].end():]
    out["_facts"], out["_judge"], out["_concl"], out["_body"] = facts, judge, concl, body
    out["result"] = result
    if ratio is not None:
        out["ratio"] = ratio
    return out


def parse_old(t, out):
    """1999~2004년 '합의권고' 사례 형식: 1. 분쟁 개요 / 2. 주요 쟁점 / 3. 사실판단 … / 4. 조정 의견."""
    out["_facts"] = sec_between(t, r"\n[ .]*1\.\s*분쟁\s*개요\s*\n", [r"\n[ .]*2\.\s"])
    out["_issue"] = sec_between(t, r"\n[ .]*2\.\s*주요\s*쟁점\s*\n", [r"\n[ .]*3\.\s"])
    out["_judge"] = sec_between(t, r"\n[ .]*4\.\s*(?:조정\s*의견|결론)[^\n]*\n", [])
    out["_body"] = sec_between(t, r"\n[ .]*3\.\s", [])
    m = re.search(r"합의권고결과\s*[:：]\s*([^\n]{2,30})", t)
    if m:
        res = m.group(1).strip()
        out["outcome"] = "합의권고 " + re.sub(r"\s+", " ", res)
        out["result"] = "기각" if re.search(r"불수락|거부", res) else ""
    return out


def finish_decision(row, p, text):
    d = {k: row[k] for k in ("id", "kind", "title", "sector", "type", "date", "url") if row.get(k)}
    d.update({k: v for k, v in p.items() if not k.startswith("_") and v not in ("", None)})
    facts, judge, concl, body = p.get("_facts", ""), p.get("_judge", ""), p.get("_concl", ""), p.get("_body", "")
    if p.get("_issue"):
        d["issue"] = shorten(p["_issue"], 240)
    if facts:
        d["facts"] = shorten(facts, 420)
    if len(judge.strip()) > 120:
        d["reasoning"] = shorten(judge, 560)
    elif concl.strip():
        d["reasoning"] = shorten(concl, 520)
    elif judge.strip():
        d["reasoning"] = shorten(judge, 520)
    elif body:
        d["reasoning"] = shorten(body[-700:], 520)
    if not (d.get("reasoning") or d.get("facts")):
        d["reasoning"] = shorten(re.sub(r"^.*?(?=\n\s*\d\.\s)", "", clean(text), count=1, flags=re.S), 520)
    d["textLen"] = len(text)
    return d


def parse_case(text, row):
    t = clean(text)
    out = {}
    secs = {}
    names = ["민원내용", "민원 내용", "신청내용", "쟁점", "처리결과", "처리 결과", "소비자 유의사항", "소비자유의사항", "유의사항", "참고", "사례개요", "사건개요", "분쟁내용", "결정", "결과"]
    marks = [(m.start(), m.end(), re.sub(r"\s+", "", m.group(1))) for m in re.finditer(r"^[ \t]*[▣■◆●◎□○ㅇ]\s*([^\n:：]{2,12}?)\s*[:：]?[ \t]*$", t, re.M)]
    for i, (s, e, nm) in enumerate(marks):
        nxt = marks[i + 1][0] if i + 1 < len(marks) else len(t)
        secs.setdefault(nm, t[e:nxt].strip())
    g = lambda *ks: next((secs[k] for k in ks if k in secs and secs[k]), "")
    out["_complaint"] = g("민원내용", "신청내용", "사례개요", "사건개요", "분쟁내용")
    out["_issue"] = g("쟁점")
    out["_result"] = g("처리결과", "결정", "결과")
    out["_tip"] = g("소비자유의사항", "유의사항")
    out["_all"] = t
    return out


def finish_case(row, p, text):
    d = {k: row[k] for k in ("id", "kind", "title", "sector", "type", "date", "url") if row.get(k)}
    if p.get("_issue"):
        d["issue"] = shorten(p["_issue"], 240)
    if p.get("_complaint"):
        d["facts"] = shorten(p["_complaint"], 420)
    if p.get("_result"):
        d["reasoning"] = shorten(p["_result"], 520)
    if p.get("_tip"):
        d["tip"] = shorten(p["_tip"], 320)
    if not (d.get("facts") or d.get("reasoning")):
        body = re.sub(r"^\s*분쟁사례\s*\n[^\n]*\n", "", clean(text))
        d["reasoning"] = shorten(body, 520)
    r = d.get("reasoning", "")
    if re.search(r"부당하다고 보기 어|지급하기 어렵|지급이 어렵|인정되기 어렵|책임이 없|책임을 지지 않|기각", r) and not re.search(r"지급하도록|배상하도록|책임이 있", r):
        d["result"] = "기각"
    elif re.search(r"지급하도록|배상하도록|책임이 있|책임을 인정|반환하도록|인용|수용", r):
        d["result"] = "인용"
    d["textLen"] = len(text)
    return d


# ---------------------------------------------------------------- 실행

def load_text(row, cache_dir):
    """첨부 중 본문이 될 파일(.hwp·.hwpx·.pdf)을 받아 글을 뽑는다. 반환: (text, filename) 또는 ('', '')."""
    cands = [f for f in row["files"] if re.search(r"\.(hwpx?|pdf)$", f[1], re.I)]
    if not cands:
        return "", ""
    # 결정례는 '게시용' 파일을 우선한다.
    cands.sort(key=lambda f: (0 if "게시" in f[1] else 1, 0 if f[1].lower().endswith(".hwp") else 1))
    for href, name in cands[:2]:
        key = re.sub(r"[^0-9A-Za-z]", "", href.split("atchFileId=")[-1])[:40] or row["id"]
        ext = os.path.splitext(name)[1].lower() or ".hwp"
        path = os.path.join(cache_dir, f"{key}{ext}")
        try:
            if not os.path.exists(path):
                data = fetch(BASE + href, binary=True)
                with open(path, "wb") as f:
                    f.write(data)
            text, method, q = extract_text.extract(path, name, ocr=False)
            if not text:
                raw = open(path, "rb").read()
                if hwp3_text.is_hwp3(raw):
                    text = hwp3_text.extract(raw)
            if text and len(re.sub(r"\s", "", text)) > 80:
                return text, name
        except Exception as e:  # noqa: BLE001
            print("  본문 읽기 실패", row["id"], name, repr(e)[:120], file=sys.stderr)
    return "", ""


def process(row, cache_dir):
    text, fname = load_text(row, cache_dir)
    if not text:
        d = {k: row[k] for k in ("id", "kind", "title", "sector", "type", "date", "url") if row.get(k)}
        d["noText"] = True
        return d
    if row["kind"] == "분쟁조정결정례":
        d = finish_decision(row, parse_decision(text, row), text)
    else:
        d = finish_case(row, parse_case(text, row), text)
    d["file"] = fname
    return d


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--prev")
    ap.add_argument("--cache", default="dispute-cache")
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--reparse", action="store_true")
    a = ap.parse_args()
    os.makedirs(a.cache, exist_ok=True)
    prev = {}
    if a.prev and os.path.exists(a.prev):
        try:
            prev = {d["id"]: d for d in json.load(open(a.prev, encoding="utf-8")).get("items", [])}
        except Exception:  # noqa: BLE001
            prev = {}
    print("목록 수집…", flush=True)
    rows = list_decisions() + list_cases()
    print("목록", len(rows), "건", flush=True)
    if len(rows) < 500:
        sys.exit("목록이 너무 적습니다(사이트 변경 또는 차단 의심). 중단합니다.")
    todo = [r for r in rows if a.reparse or r["id"] not in prev or prev[r["id"]].get("noText")]
    if a.limit:
        todo = todo[: a.limit]
    print("새로 읽을 글", len(todo), flush=True)
    new = {}
    with cf.ThreadPoolExecutor(max_workers=a.workers) as ex:
        futs = {ex.submit(process, r, a.cache): r for r in todo}
        for n, f in enumerate(cf.as_completed(futs), 1):
            r = futs[f]
            try:
                new[r["id"]] = f.result()
            except Exception as e:  # noqa: BLE001
                print("실패", r["id"], repr(e)[:150], file=sys.stderr)
            if n % 50 == 0:
                print(f"  {n}/{len(todo)}", flush=True)
    items = []
    for r in rows:
        d = new.get(r["id"]) or prev.get(r["id"])
        if not d:
            d = {k: r[k] for k in ("id", "kind", "title", "sector", "type", "date", "url") if r.get(k)}
            d["noText"] = True
        else:
            # 목록 쪽 값(제목·날짜)이 바뀌었을 수 있으니 갱신
            d.update({k: r[k] for k in ("title", "date", "sector", "type") if r.get(k)})
        items.append(d)
    items.sort(key=lambda d: (d.get("date", ""), d["id"]), reverse=True)
    data = {
        "asOf": (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=9)).strftime("%Y-%m-%d"),
        "source": SOURCE,
        "counts": {
            "decisions": sum(1 for d in items if d["kind"] == "분쟁조정결정례"),
            "cases": sum(1 for d in items if d["kind"] == "분쟁조정사례"),
            "noText": sum(1 for d in items if d.get("noText")),
        },
        "items": items,
    }
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
    print(json.dumps(data["counts"], ensure_ascii=False), "→", a.out, os.path.getsize(a.out), "bytes")


if __name__ == "__main__":
    main()
