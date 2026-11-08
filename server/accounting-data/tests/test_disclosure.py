import os, sys, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
from parse_disclosure import parse_disclosure, won, parse_action  # noqa: E402


def sample(name):
    return open(os.path.join(HERE, name), encoding="utf-8").read()


class DisclosureTest(unittest.TestCase):
    def test_amounts(self):
        self.assertEqual(won("600만원"), 6_000_000)
        self.assertEqual(won("1억 740만원"), 107_400_000)
        self.assertEqual(won("6,000,000원"), 6_000_000)
        self.assertEqual(parse_action("과징금 90,000,000")[1], 90_000_000)
        self.assertEqual(parse_action("증권발행제한 9월 부과")[2], 9)

    def test_two_companies_one_file(self):
        r = parse_disclosure(sample("sample_disc_fine.txt"))
        self.assertEqual([x["company"] for x in r], ["㈜울트라브이", "㈜업타임홀딩스"])
        self.assertEqual([x["fineWon"] for x in r], [90_000_000, 21_400_000])
        self.assertEqual(r[0]["date"], "2024-10-02")
        # 각 회사에는 그 회사 이야기만 남는다
        self.assertTrue(any("울트라브이는" in f for f in r[0]["violations"][0]["facts"]))
        self.assertFalse(any("업타임홀딩스는" in f for f in r[0]["violations"][0]["facts"]))

    def test_etc_companies_share_statement(self):
        r = parse_disclosure(sample("sample_disc_multi.txt"))
        self.assertEqual(len(r), 6)
        self.assertEqual(r[3]["company"], "디에스티㈜")
        self.assertEqual(r[3]["issueLimitMonths"], 1)
        self.assertTrue(all(x["violations"][0]["facts"] for x in r))  # '등 6개사' 문장은 모두에게

    def test_many_companies_header_has_deung(self):
        r = parse_disclosure(sample("sample_disc_many.txt"))
        self.assertEqual(len(r), 11)
        self.assertEqual(r[0]["company"], "㈜멈스")

    def test_masked(self):
        r = parse_disclosure(sample("sample_disc_masked.txt"))
        self.assertEqual(len(r), 5)
        self.assertTrue(all(x["masked"] for x in r))
        self.assertEqual(r[0]["role"], "매출인")


if __name__ == "__main__":
    unittest.main()
