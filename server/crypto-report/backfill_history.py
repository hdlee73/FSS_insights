"""2020년 이후 일별 과거 자료 백필(거래소별 거래대금·BTC 가격·김치 프리미엄).

거래소 공개 일봉 API에서 '현재 거래대금 상위 종목'의 과거 일별 거래대금을 모아 합산한다.
  - 업비트: 일봉 거래대금(원) 그대로. 2020년부터 전부.
  - 코인원: 일봉 quote_volume(원) 그대로. 2020년부터(상장 이후).
  - 빗썸: 일봉 API가 최근 약 200일뿐이라 그 구간만, 거래량×종가로 추정.
  - 코빗: 일봉 API(v2)를 시도하고, 실패하면 건너뜀.
  - 고팍스: 원화 마켓 수집이 막혀 있어 건너뜀.
한계: 현재 상장 종목 기준이라 이미 상장폐지된 코인은 빠진다(소급 추정). 결과는 data/history_long.json.
BTC 김치 프리미엄은 업비트 BTC 일봉 종가 ÷ (Coinbase BTC-USD 일봉 종가 × 원/달러 환율) − 1.
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests

import collectors as C

log = logging.getLogger("backfill")
START = "2020-01-01"
KST = timezone(timedelta(hours=9))
TOP_N = {"upbit": 90, "coinone": 70, "bithumb": 60, "korbit": 40}
COLS = ["date", "upbit", "bithumb", "coinone", "korbit", "btc_price", "btc_value", "btc_dom", "kimchi"]


def kst_date(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, KST).strftime("%Y-%m-%d")


def upbit_days(session, market: str) -> dict[str, tuple[float, float]]:
    """{날짜: (거래대금 원, 종가)} — 업비트 일봉(09시 KST 기준)."""
    out, to = {}, None
    for _ in range(40):
        params = {"market": market, "count": 200}
        if to:
            params["to"] = to
        rows = C.get_json(session, "https://api.upbit.com/v1/candles/days", params=params)
        if not rows:
            break
        for r in rows:
            d = r["candle_date_time_kst"][:10]
            if d >= START:
                out[d] = (float(r["candle_acc_trade_price"]), float(r["trade_price"]))
        oldest = rows[-1]["candle_date_time_utc"]
        if oldest[:10] < START or len(rows) < 200:
            break
        to = oldest + "Z"
        time.sleep(0.12)
    return out


def coinone_days(session, sym: str) -> dict[str, tuple[float, float]]:
    out, ts = {}, None
    for _ in range(12):
        params = {"interval": "1d", "size": 500}
        if ts:
            params["timestamp"] = ts
        d = C.get_json(session, f"https://api.coinone.co.kr/public/v2/chart/KRW/{sym}", params=params)
        rows = d.get("chart") or []
        if not rows:
            break
        for r in rows:
            day = kst_date(int(r["timestamp"]))
            if day >= START:
                out[day] = (float(r["quote_volume"]), float(r["close"]))
        oldest = min(int(r["timestamp"]) for r in rows)
        if kst_date(oldest) < START or d.get("is_last"):
            break
        ts = oldest - 86_400_000
        time.sleep(0.12)
    return out


def bithumb_days(session, sym: str) -> dict[str, tuple[float, float]]:
    d = C.get_json(session, f"https://api.bithumb.com/public/candlestick/{sym}_KRW/24h")
    out = {}
    for r in d.get("data") or []:
        day = kst_date(int(r[0]))
        close, vol = float(r[2]), float(r[5])
        out[day] = (close * vol, close)   # 거래대금 API가 없어 종가×거래량으로 추정
    return out


def korbit_days(session, sym: str) -> dict[str, tuple[float, float]]:
    out, end = {}, None
    for _ in range(14):
        params = {"symbol": f"{sym.lower()}_krw", "interval": "1D", "limit": 200}
        if end:
            params["end"] = end
        d = C.get_json(session, "https://api.korbit.co.kr/v2/candles", params=params)
        rows = d.get("data") or []
        if not rows:
            break
        for r in rows:
            day = kst_date(int(r["timestamp"]))
            if day >= START:
                close = float(r["close"])
                out[day] = (close * float(r["volume"]), close)
        oldest = min(int(r["timestamp"]) for r in rows)
        if kst_date(oldest) < START or len(rows) < 200:
            break
        end = oldest - 1
        time.sleep(0.2)
    return out


FETCH = {"upbit": upbit_days, "coinone": coinone_days, "bithumb": bithumb_days, "korbit": korbit_days}


def coinbase_btc(session) -> dict[str, float]:
    """Coinbase BTC-USD 일봉 종가(UTC 일)."""
    out, start = {}, datetime.strptime(START, "%Y-%m-%d").replace(tzinfo=timezone.utc)
    end_all = datetime.now(timezone.utc)
    while start < end_all:
        end = min(start + timedelta(days=290), end_all)
        rows = C.get_json(session, "https://api.exchange.coinbase.com/products/BTC-USD/candles",
                          params={"granularity": 86400, "start": start.strftime("%Y-%m-%dT00:00:00Z"),
                                  "end": end.strftime("%Y-%m-%dT00:00:00Z")})
        for r in rows or []:
            out[datetime.fromtimestamp(r[0], timezone.utc).strftime("%Y-%m-%d")] = float(r[4])
        start = end + timedelta(days=1)
        time.sleep(0.2)
    return out


def usdkrw_daily(session) -> dict[str, float]:
    d = C.get_json(session, f"https://api.frankfurter.dev/v1/{START}..", params={"base": "USD", "symbols": "KRW"})
    return {k: float(v["KRW"]) for k, v in (d.get("rates") or {}).items() if "KRW" in v}


def aggregate(per_ex: dict[str, dict[str, dict[str, tuple[float, float]]]], cb: dict[str, float], fx: dict[str, float]) -> list[list]:
    """per_ex[거래소][종목][날짜]=(거래대금, 종가) → 날짜별 행(COLS 순서)."""
    days = sorted({d for ex in per_ex.values() for sym in ex.values() for d in sym})
    fx_days = sorted(fx)
    rows, last_fx, fi = [], None, 0
    for d in days:
        while fi < len(fx_days) and fx_days[fi] <= d:
            last_fx = fx[fx_days[fi]]
            fi += 1
        tot = {ex: sum(v[d][0] for v in syms.values() if d in v) for ex, syms in per_ex.items()}
        btc_v = sum(syms["BTC"][d][0] for syms in per_ex.values() if d in syms.get("BTC", {}))
        all_v = sum(tot.values())
        up_btc = per_ex.get("upbit", {}).get("BTC", {}).get(d)
        kimchi = None
        if up_btc and cb.get(d) and last_fx:
            kimchi = up_btc[1] / (cb[d] * last_fx) - 1
        rows.append([d] + [round(tot.get(ex)) if tot.get(ex) else None for ex in ("upbit", "bithumb", "coinone", "korbit")]
                    + [up_btc[1] if up_btc else None, round(btc_v) if btc_v else None,
                       round(btc_v / all_v, 4) if all_v else None, round(kimchi, 4) if kimchi is not None else None])
    return rows


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--force", action="store_true", help="이미 있어도 다시 받기")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    path = args.out / "data" / "history_long.json"
    if path.exists() and not args.force:
        log.info("history_long.json 이 이미 있어 건너뜀(--force 로 재수집)")
        return 0
    per_ex: dict[str, dict] = {}
    with requests.Session() as s:
        for ex, n in TOP_N.items():
            try:
                tickers = sorted(C.FETCHERS[ex](s), key=lambda t: t.value_24h, reverse=True)
            except Exception as e:
                log.warning("%s 종목 목록 실패(건너뜀): %s", ex, e)
                continue
            syms = [t.symbol for t in tickers[:n]]
            if "BTC" not in syms:
                syms.append("BTC")
            got, fails = {}, 0
            for sym in syms:
                try:
                    market = f"KRW-{sym}" if ex == "upbit" else sym
                    r = FETCH[ex](s, market)
                    if r:
                        got[sym] = r
                except Exception as e:
                    fails += 1
                    if fails <= 3:
                        log.warning("%s %s 실패: %s", ex, sym, e)
                    if fails >= 8 and not got:
                        log.warning("%s 연속 실패로 중단", ex)
                        break
                time.sleep(0.05)
            log.info("%s: %d/%d 종목, 날짜 %s", ex, len(got), len(syms),
                     (min(d for v in got.values() for d in v), max(d for v in got.values() for d in v)) if got else None)
            if got:
                per_ex[ex] = got
        if not per_ex:
            log.error("받은 자료가 없어 중단")
            return 0
        try:
            cb = coinbase_btc(s)
        except Exception as e:
            log.warning("Coinbase 일봉 실패: %s", e)
            cb = {}
        try:
            fx = usdkrw_daily(s)
        except Exception as e:
            log.warning("환율 이력 실패: %s", e)
            fx = {}
    rows = aggregate(per_ex, cb, fx)
    cov = {ex: {"from": min(d for v in syms.values() for d in v), "to": max(d for v in syms.values() for d in v),
                "markets": len(syms), "estimated": ex in ("bithumb", "korbit")} for ex, syms in per_ex.items()}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"updated": datetime.now(KST).strftime("%Y-%m-%d %H:%M KST"), "start": START, "cols": COLS,
                                "coverage": cov, "rows": rows}, ensure_ascii=False, separators=(",", ":")), "utf-8")
    log.info("완료: %d일", len(rows))
    return 0


if __name__ == "__main__":
    sys.exit(main())
