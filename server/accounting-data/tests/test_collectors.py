import os, sys, unittest
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import wiki_collect, kasb_replies  # noqa: E402

WIKI = ('x{\\"slug\\":\\"202604A\\",\\"title\\":\\"오프테이크 약정\\",\\"source\\":\\"IFRS 해석위원회\\",\\"date\\":\\"2026-04-29\\",\\"stds\\":[\\"1116\\"]},'
        '{\\"slug\\":\\"SSI-1\\",\\"title\\":\\"질의 제목\\",\\"source\\":\\"신속처리질의\\",\\"date\\":\\"2021-08-23\\"},'
        '{\\"slug\\":\\"SSI-1\\",\\"title\\":\\"중복\\",\\"source\\":\\"신속처리질의\\",\\"date\\":\\"2021-08-23\\"}')

KASB = """<tr><td class="col_none">2184</td><td class="col_none">IFRS 해석위원회 논의 결과</td>
<td class="left"><a href="javascript:void(0);" onclick="javascript:fn_Detail('40683','016002');">배터리 리스</a></td>
<td></td><td><p class="board_date">2026-04-30</p></td><td>2026-09-30</td></tr>
<tr><td class="col_none"><strong>공지</strong></td><td></td><td class="left"><a onclick="javascript:fn_Detail('1','016009');">공지</a></td><td></td><td></td><td></td></tr>"""


class CollectorTests(unittest.TestCase):
    def test_wiki_parse_classifies_and_dedups(self):
        r = wiki_collect.parse_list(WIKI, "", "K-IFRS")
        self.assertEqual(2, len(r))
        self.assertEqual("interp", r[0]["kind"])
        self.assertEqual("https://accountingwiki.co.kr/qnas/202604A", r[0]["url"])
        self.assertEqual("qna", r[1]["kind"])
        self.assertIn("회계위키 경유", r[0]["source"])

    def test_wiki_audit_area_is_audit_case(self):
        self.assertEqual("audit-case", wiki_collect.classify("/audit", "금융감독원")[0])
        self.assertEqual("audit-case", wiki_collect.classify("", "ESMA 집행사례")[0])

    def test_kasb_parse_skips_notices(self):
        r = kasb_replies.parse_page(KASB)
        self.assertEqual(1, len(r))
        self.assertEqual("2026-04-30", r[0]["date"])
        self.assertTrue(r[0]["url"].endswith("View016002.do?seq=40683&ctgCd=016002"))


if __name__ == "__main__":
    unittest.main()
