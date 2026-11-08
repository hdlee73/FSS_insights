#!/usr/bin/env python3
"""회계 데이터 수집기: 금감원 회계감리결과제재 + 회계 게시판(질의회신·해석·감리지적사례) → JSON.

사용: python3 build_accounting.py OUT_DIR [--cache DIR] [--delay 0.5]
  OUT_DIR/audit_actions.json  회계감리결과제재 (지적사항·조치 파싱)
  OUT_DIR/board_index.json    질의회신·해석·감리지적사례 게시판 색인(제목·일자·원문링크)
  OUT_DIR/disclosure_sanctions.json  공시조사결과제재(2020년 이후, 첨부를 읽어 회사별로 나눈 것)
금감원 서버 부담을 줄이려고 요청 사이에 지연을 두고, 이미 받은 첨부는 --cache에서 재사용한다.
"""
import argparse, html, json, os, re, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fetch_list import BASE, get, fetch_all  # noqa: E402
from doc_text import doc_text  # noqa: E402
from parse_audit import parse_doc  # noqa: E402
import kasb_replies  # noqa: E402
import wiki_collect  # noqa: E402
import build_disclosure  # noqa: E402

PARSER_VERSION = 1
BOARDS = [
    ("qna", "일반기업회계기준 질의회신", "B0000133", "200443"),
    ("interp", "기업회계기준해석", "B0000125", "200432"),
    ("audit-case", "심사·감리지적사례", "B0000135", "200448"),
]


def build_audit(cache_dir, delay):
    rows = fetch_all("02", "200617")
    out = []
    for r in rows:
        rec = {k: r[k] for k in ("slno", "company", "auditPeriod", "actionDate", "market", "viewUrl")}
        rec["files"] = [{"name": f["name"], "url": f["url"]} for f in r["files"]]
        rec["parserVersion"] = PARSER_VERSION
        text = ""
        for i, f in enumerate(r["files"][:1]):
            ext = os.path.splitext(f["name"])[1].lower() or ".hwp"
            path = os.path.join(cache_dir, f"{r['slno']}_{i}{ext}")
            try:
                if not os.path.exists(path):
                    data = get(f["url"], binary=True)
                    with open(path, "wb") as fh:
                        fh.write(data)
                    time.sleep(delay)
                text = doc_text(path)
            except Exception as e:  # noqa: BLE001
                rec["error"] = f"{type(e).__name__}: {e}"[:200]
        rec.update(parse_doc(text) if text else {"parseStatus": "failed", "findings": [], "actions": [], "flags": {}, "notes": [], "violationTypes": [], "accounts": []})
        if rec.get("market") in ("", "기타") and rec.get("marketFromDoc"):
            rec["market"] = rec["marketFromDoc"]  # 목록 칸이 '기타'면 본문 기재(비상장 등)를 쓴다
        out.append(rec)
    out.sort(key=lambda x: x["actionDate"], reverse=True)
    return out


def merge_prev(new, prev_path):
    """금감원은 최근 3년 공시만 제공하므로, 이전에 수집한 건을 보존해 누적한다(10년까지 보관)."""
    if not prev_path or not os.path.exists(prev_path):
        return new, 0
    try:
        old = json.load(open(prev_path, encoding="utf-8")).get("items", [])
    except (OSError, ValueError):
        return new, 0
    keys = {(x.get("slno") or x["company"] + "|" + x["actionDate"]) for x in new}
    keep_from = "%d-%s" % (int(time.strftime("%Y")) - 10, time.strftime("%m-%d"))  # 10년 이내만 보관
    kept = [x for x in old if (x.get("slno") or x["company"] + "|" + x["actionDate"]) not in keys and x.get("actionDate", "") >= keep_from]
    merged = new + kept
    merged.sort(key=lambda x: x["actionDate"], reverse=True)
    return merged, len(kept)


def parse_board(page, bid, menu):
    body = page[page.find("<tbody"):page.find("</tbody>")]
    items = []
    for tr in re.findall(r"<tr.*?</tr>", body, re.S):
        m = re.search(r'<a href="(/fss/bbs/%s/view\.do\?nttId=(\d+)[^"]*)">(.*?)</a>' % bid, tr, re.S)
        if not m:
            continue
        cells = [re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", c))).strip() for c in re.findall(r"<td[^>]*>(.*?)</td>", tr, re.S)]
        date = next((c for c in cells if re.fullmatch(r"\d{4}-\d{2}-\d{2}", c)), "")
        files = [{"name": html.unescape(n).strip(), "url": BASE + html.unescape(u)} for u, n in
                 re.findall(r'<a href="(/fss/cmmn/file/fileDown\.do[^"]*)">.*?<span class="name">(.*?)</span>', tr, re.S)]
        if not files:  # 일부 게시판은 이름 칸이 다름
            files = [{"name": "첨부", "url": BASE + html.unescape(u)} for u in re.findall(r'href="(/fss/cmmn/file/fileDown\.do[^"]*)"', tr)]
        title = html.unescape(re.sub(r"<[^>]+>", "", m.group(3))).strip()
        meta = [c for c in cells[1:] if c and c != date and not re.fullmatch(r"[\d,]+", c) and "첨부" not in c and "다운로드" not in c and c != title and not c.endswith((".hwp", ".hwpx", ".pdf"))]
        items.append({
            "id": m.group(2), "title": html.unescape(re.sub(r"<[^>]+>", "", m.group(3))).strip(),
            "date": date, "meta": meta[:4], "url": BASE + html.unescape(m.group(1)).replace("&amp;", "&"), "files": files[:3],
        })
    return items


def build_boards(delay):
    result = []
    for kind, label, bid, menu in BOARDS:
        page_no = 1
        while True:
            url = f"{BASE}/fss/bbs/{bid}/list.do?menuNo={menu}&pageIndex={page_no}&pageUnit=100"
            items = parse_board(get(url), bid, menu)
            for it in items:
                it["kind"], it["kindLabel"] = kind, label
                it["source"] = "금융감독원 / 직접 수집(fss.or.kr 회계 게시판)"
            result += items
            time.sleep(delay)
            if len(items) < 100 or page_no >= 30:
                break
            page_no += 1
    try:  # 한국회계기준원(K-IFRS 등) 질의회신 요약 전체
        result += kasb_replies.fetch_all(delay)
    except Exception as e:  # noqa: BLE001
        print("KASB 질의회신 수집 실패(금감원 자료만 사용):", e, file=sys.stderr)
    try:  # 회계위키: K-IFRS·일반기업회계기준·회계감사·내부회계 질의회신, 해석, 감리지적사례
        result += wiki_collect.fetch_all()
    except Exception as e:  # noqa: BLE001
        print("회계위키 수집 실패(다른 출처만 사용):", e, file=sys.stderr)
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--cache", default="cache")
    ap.add_argument("--delay", type=float, default=0.5)
    ap.add_argument("--prev", default="", help="이전 audit_actions.json 경로(누적 보관용)")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    os.makedirs(a.cache, exist_ok=True)
    audit = build_audit(a.cache, a.delay)
    audit, carried = merge_prev(audit, a.prev)
    boards = build_boards(a.delay)
    stamp = time.strftime("%Y-%m-%dT%H:%M:%S+09:00", time.gmtime(time.time() + 9 * 3600))
    bad = [x for x in audit if x["parseStatus"] != "ok"]
    json.dump({"generatedAt": stamp, "count": len(audit), "unparsed": len(bad), "items": audit},
              open(os.path.join(a.out, "audit_actions.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    json.dump({"generatedAt": stamp, "count": len(boards), "items": boards},
              open(os.path.join(a.out, "board_index.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    disc, disc_failed, disc_posts = build_disclosure.build(a.cache, a.delay)
    json.dump({"generatedAt": stamp, "count": len(disc), "posts": disc_posts, "failed": disc_failed, "items": disc},
              open(os.path.join(a.out, "disclosure_sanctions.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print(f"disclosure={len(disc)} (게시글 {disc_posts}건, 읽지 못한 첨부 {len(disc_failed)}건)")
    print(f"audit={len(audit)} (이전 누적 {carried}건 포함, 미분류 {len(bad)}), board={len(boards)}")
    if len(audit) < 20 or len(boards) < 100:  # 사이트 개편 등으로 급감하면 실패시켜 기존 데이터를 보존
        sys.exit("수집 건수가 비정상적으로 적습니다. 파서 점검 필요")
    if len(disc) < 150:
        sys.exit("공시조사결과제재 수집 건수가 비정상적으로 적습니다. 파서 점검 필요")


if __name__ == "__main__":
    main()
