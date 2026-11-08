"""회계위키(accountingwiki.co.kr) 목록 페이지에서 질의회신·해석·감리지적사례 색인을 수집한다.

robots.txt가 허용하는 공개 목록 페이지(/qnas, /kgaap/qnas, /audit/qnas, /icfr/qnas)만 읽는다(/api/, /search 제외).
목록 페이지에 제목·기관·일자가 모두 실려 있어 4번의 요청으로 전체를 얻는다. 본문은 가져오지 않고 원문 링크만 둔다.
"""
import json, re
from fetch_list import get

WIKI = "https://accountingwiki.co.kr"
AREAS = [  # (경로, 영역명)
    ("", "K-IFRS"), ("/kgaap", "일반기업회계기준"), ("/audit", "회계감사기준"), ("/icfr", "내부회계관리제도"),
]
ITEM = re.compile(r'\{"slug":"([^"]+)","title":"((?:[^"\\]|\\.)*)","source":"([^"]*)","date":"([^"]*)"')
SOURCE_TAG = "회계위키(accountingwiki.co.kr)"


def classify(area_path, source):
    """(kind, kindLabel)"""
    if area_path == "/audit":
        return "audit-case", "감리지적사례"
    if source == "ESMA 집행사례":
        return "audit-case", "감리지적사례"
    if source == "IFRS 해석위원회":
        return "interp", "해석"
    return "qna", "질의회신"


def parse_list(text, area_path, area_name):
    text = text.replace('\\"', '"')
    out, seen = [], set()
    for slug, title, source, date in ITEM.findall(text):
        if slug in seen:
            continue
        seen.add(slug)
        kind, label = classify(area_path, source)
        title = json.loads('"' + title + '"') if "\\" in title else title
        out.append({
            "id": f"wiki{area_path.replace('/', '-')}-{slug}", "title": title, "date": date if len(date) == 10 else date,
            "meta": [area_name, source], "url": f"{WIKI}{area_path}/qnas/{slug}", "kind": kind, "kindLabel": label,
            "files": [], "source": f"{source} / 회계위키 경유",
        })
    return out


def fetch_all():
    items = []
    for path, name in AREAS:
        items += parse_list(get(f"{WIKI}{path}/qnas"), path, name)
    return items
