"""공시조사결과제재(금감원 B0000161: 증권선물위원회·금융위원회 공시위반 조치 내역)를 회사 단위로 풀어 JSON으로 만든다.

게시판 글 하나에 첨부(HWP)가 여럿이고, 첨부 하나에 회사가 여럿 들어 있기도 하다. 그래서 목록을 그대로 쓰지 않고
첨부의 '제재대상·제재조치내용·제재대상사실'을 읽어 회사마다 한 건씩 만든다(parse_disclosure.py).
2020년 이후 글만 가져온다.
"""
import html
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fetch_list import BASE, get  # noqa: E402
from doc_text import doc_text  # noqa: E402
from parse_disclosure import parse_disclosure  # noqa: E402

BOARD = "B0000161"
MENU = "200478"
SINCE = "2020-01-01"
PARSER_VERSION = 1


def list_posts():
    url = f"{BASE}/fss/bbs/{BOARD}/list.do?menuNo={MENU}&pageIndex=1&pageUnit=300"
    page = get(url)
    body = page[page.find("<tbody"):page.find("</tbody>")]
    posts = []
    for tr in re.findall(r"<tr>.*?</tr>", body, re.S):
        m = re.search(r'<td class="title"><a href="([^"]*nttId=(\d+)[^"]*)">(.*?)</a>', tr, re.S)
        if not m:
            continue
        dates = re.findall(r"(\d{4}-\d{2}-\d{2})", re.sub(r"<[^>]+>", " ", tr))
        files = [{"url": BASE + html.unescape(u).replace("&amp;", "&"), "name": html.unescape(n).strip()}
                 for u, n in re.findall(r'<a href="(/fss/cmmn/file/fileDown\.do[^"]*)">.*?<span class="name">(.*?)</span>', tr, re.S)]
        posts.append({
            "ntt": m.group(2), "title": html.unescape(re.sub(r"<[^>]+>", "", m.group(3))).strip(),
            "date": dates[0] if dates else "", "files": files,
            "url": f"{BASE}/fss/bbs/{BOARD}/view.do?nttId={m.group(2)}&menuNo={MENU}",
        })
    return posts


def committee_of(title):
    m = re.search(r"(\d{4})년\s*(?:제\s*\d+차\s*)?(증권선물위원회|금융위원회)", title)
    both = "증권선물위원회" in title and "금융위원회" in title
    return "증권선물위원회·금융위원회" if both else (m.group(2) if m else "")


def build(cache_dir, delay=0.4, since=SINCE):
    posts = [p for p in list_posts() if p["date"] >= since]
    items, failed = [], []
    for p in posts:
        for i, f in enumerate(p["files"]):
            ext = os.path.splitext(f["name"])[1].lower() or ".hwp"
            path = os.path.join(cache_dir, f"disc_{p['ntt']}_{i}{ext}")
            try:
                if not os.path.exists(path):
                    with open(path, "wb") as fh:
                        fh.write(get(f["url"], binary=True))
                    time.sleep(delay)
                recs = parse_disclosure(doc_text(path))
            except Exception as e:  # noqa: BLE001
                failed.append(f"{p['ntt']}_{i}: {type(e).__name__}: {e}"[:160])
                continue
            if not recs:
                failed.append(f"{p['ntt']}_{i}: 형식 불일치")
                continue
            for j, r in enumerate(recs):
                r.update({
                    "id": f"{p['ntt']}-{i}-{j}", "postDate": p["date"], "round": p["title"],
                    "committee": committee_of(p["title"]), "postUrl": p["url"],
                    "fileName": f["name"], "fileUrl": f["url"],
                })
                items.append(r)
    items.sort(key=lambda x: (x["date"] or x["postDate"], x["id"]), reverse=True)
    return items, failed, len(posts)
