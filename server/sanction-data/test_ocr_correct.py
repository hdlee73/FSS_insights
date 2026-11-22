"""ocr_correct 단위 시험: python3 server/sanction-data/test_ocr_correct.py"""
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(__file__))
import ocr_correct as oc  # noqa: E402


def case(t, laws, ocr=False):
    return {"ocr": ocr, "items": [{"t": t, "laws": laws}]}


def main():
    st = Counter()
    # 약어
    assert oc.fix_acronyms("615 판매 관련 녹취의무 위반", st) == "ELS 판매 관련 녹취의무 위반"
    assert oc.fix_acronyms("결산업무 부당처리 및 815기준 자기자본비율", st).count("BIS기준") == 1
    assert oc.fix_acronyms("보험계약 615건", st) == "보험계약 615건"          # 실제 숫자는 그대로
    assert oc.fix_acronyms("과태료 1,815 백만원 부과", st) == "과태료 1,815 백만원 부과"
    assert oc.fix_acronyms("ELS 판매", st) == "ELS 판매"
    assert oc.fix_acronyms("DL5 판매", st) == "DLS 판매"
    # 사전 기반 보정
    good = [case("보험업법 위반 보험계약 체결", ["보험업법 제97조", "자본시장법 제71조", "자본시장법 시행령 제68조"]) for _ in range(30)]
    bad = case("615 판매 관련 보혐계약 엽무 위반", ["지본시장법 시행령 제68조", "「보혐업법 제97조"], ocr=True)
    cs = good + [bad]
    oc.correct_cases(cs, st)
    it = bad["items"][0]
    assert it["t"] == "ELS 판매 관련 보험계약 업무 위반", it["t"]
    assert it["laws"] == ["자본시장법 시행령 제68조", "보험업법 제97조"], it["laws"]
    # 글자 층 자료(ocr=False)는 손대지 않는다
    assert good[0]["items"][0]["t"] == "보험업법 위반 보험계약 체결"
    # 멀쩡한 낱말은 건드리지 않는다
    ok = case("부당업무 및 이용에 관한 지급", [], ocr=True)
    oc.correct_cases(good + [ok])
    assert ok["items"][0]["t"] == "부당업무 및 이용에 관한 지급"
    print("ok", dict(st))


if __name__ == "__main__":
    main()
