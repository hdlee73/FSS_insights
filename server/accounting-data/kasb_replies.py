"""한국회계기준원(KASB) '질의회신 요약 전체' 목록 수집 (공개 게시판, robots.txt 없음)."""
import html, re, time
from fetch_list import get

BASE = "https://www.kasb.or.kr"
LIST = BASE + "/front/board/allReplySummaryList.do"


def parse_page(page):
    items = []
    for tr in re.findall(r"<tr>(.*?)</tr>", page, re.S):
        m = re.search(r"fn_Detail\('(\d+)','(\d+)'\);\">(.*?)</a>", tr, re.S)
        tds = re.findall(r"<td[^>]*>(.*?)</td>", tr, re.S)
        if not m or len(tds) < 6:
            continue
        clean = lambda s: re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", s))).strip()
        no = clean(tds[0])
        if not no.isdigit():  # 공지 제외
            continue
        seq, ctg = m.group(1), m.group(2)
        items.append({
            "id": "kasb-" + seq, "title": clean(m.group(3)), "date": clean(tds[4]),
            "meta": [x for x in (clean(tds[1]),) if x],
            "url": f"{BASE}/front/board/View{ctg}.do?seq={seq}&ctgCd={ctg}",
            "kind": "qna", "kindLabel": "KASB 질의회신", "files": [], "source": "한국회계기준원 / 직접 수집(kasb.or.kr 질의회신 요약 전체)",
        })
    return items


def fetch_all(delay=0.3, max_pages=400):
    out, seen = [], set()
    for p in range(1, max_pages + 1):
        items = parse_page(get(f"{LIST}?page={p}"))
        new = [i for i in items if i["id"] not in seen]
        if not new:
            break
        seen.update(i["id"] for i in new)
        out += new
        time.sleep(delay)
    return out
