import os, sys, unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from parse_audit import parse_doc, money_to_won, parse_decision  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


def sample(name):
    return open(os.path.join(HERE, name), encoding="utf-8").read()


class ParseAuditTest(unittest.TestCase):
    def test_money(self):
        self.assertEqual(money_to_won("3,052백만원"), [3_052_000_000])
        self.assertEqual(money_to_won("총 3.2억원 / 5만원"), [320_000_000, 50_000])

    def test_decision(self):
        d = parse_decision("(제19차 증권선물위원회, 2026.10.28. 의결)")
        self.assertEqual((d["round"], d["date"], d["kind"]), (19, "20261028", "의결"))

    def test_fidelix(self):
        r = parse_doc(sample("sample_fidelix.txt"))
        self.assertEqual(r["parseStatus"], "ok")
        self.assertEqual(len(r["findings"]), 2)
        self.assertIn("허위계상", r["violationTypes"])
        self.assertEqual(r["flags"]["auditorDesignationYears"], 3)
        self.assertTrue(r["flags"]["fine"] and not r["flags"]["fineFinal"])
        self.assertTrue(r["flags"]["prosecution"] and r["flags"]["referral"] and r["flags"]["dismissalRec"])
        self.assertEqual(r["marketFromDoc"], "코스닥시장")

    def test_circle_bullet_and_unlisted(self):
        r = parse_doc(sample("sample_panakeia.txt"))
        self.assertEqual(r["parseStatus"], "ok")
        self.assertEqual(r["findings"][0]["no"], 1)
        self.assertEqual(r["marketFromDoc"], "비상장")
        self.assertTrue(r["flags"]["correction"])

    def test_garbage_does_not_raise(self):
        r = parse_doc("전혀 다른 문서")
        self.assertEqual(r["parseStatus"], "failed")


if __name__ == "__main__":
    unittest.main()
