"""국내 가상자산 시장 일보 생성기.

사용:
  python build_daily.py --out ../../site            # 실제 수집(GitHub Actions)
  python build_daily.py --out /tmp/site --sample    # 네트워크 없이 샘플 데이터로 모양 확인

집계는 한국시간 매일 12:00, 24:00 두 번(슬롯 1200, 2400). KEY 는 YYYYMMDD-슬롯, 예: 20261110-1200.
출력(out 아래):
  data/latest.json                  가장 최근 집계 스냅샷 전체
  data/daily/KEY.json               집계별 스냅샷 보관
  data/history.json                 집계별 핵심 지표 시계열(도미넌스·거래대금 등)
  data/exchanges/KEY.json           거래소별 상장 종목·주요 코인 가격(거래소별 시계열·상장/상폐 비교용)
  data/coins.json                   업비트 주요 코인 시세와 당일·1년 그래프 데이터
  reports/YYYY/KEY.pdf, KEY.hwpx    일보(PDF, HWPX)
  reports/latest.pdf, latest.hwpx, reports/index.json
환경변수: COINMARKETCAP_KEY(없으면 CoinGecko), COINGECKO_KEY, KOREAEXIM_KEY (없어도 동작)
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import random
import shutil
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import requests

import collectors as C
from analysis import build_coins, build_snapshot, history_entry, merge_history, previous
from report_hwpx import render_hwpx
from report_pdf import render_pdf
from slots import SLOT_LABEL, SLOTS, resolve_slot, slot_key, slot_time

log = logging.getLogger("daily")
KST = timezone(timedelta(hours=9))
MAJORS = ["BTC", "ETH", "XRP", "SOL", "DOGE", "ADA", "USDT"]


def _read_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return default
    except (OSError, ValueError) as e:
        log.warning("%s 읽기 실패, 기본값 사용: %s", path, e)
        return default


def _write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(obj, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    tmp.replace(path)  # 쓰다 실패해도 기존 파일은 온전하게


# ---------------------------------------------------------------- 수집

def collect(session: requests.Session) -> dict:
    tickers, errors = {}, {}
    for ex, fn in C.FETCHERS.items():
        try:
            ts = fn(session)
            if not ts:
                raise ValueError("종목이 0개")
            tickers[ex] = ts
            log.info("%s %d종목", ex, len(ts))
        except Exception as e:
            errors[C.EXCHANGE_NAMES[ex]] = str(e)
            log.error("%s 수집 실패: %s", ex, e)
    global_ = btc_usd = usdkrw = fx_src = None
    try:
        global_ = C.fetch_global(session, os.environ.get("COINGECKO_KEY") or None,
                                 os.environ.get("COINMARKETCAP_KEY") or None)
    except Exception as e:
        errors["글로벌 시총"] = str(e)
        log.error("글로벌 시총 수집 실패: %s", e)
    try:
        btc_usd = C.fetch_coinbase_btc_usd(session)
    except Exception as e:
        errors["Coinbase"] = str(e)
        if global_:
            btc_usd = global_.get("btc_price_usd_cg")
    try:
        usdkrw, fx_src = C.fetch_usdkrw(session, os.environ.get("KOREAEXIM_KEY") or None)
    except Exception as e:
        errors["환율"] = str(e)
    symbol_caps = None
    try:
        symbol_caps = C.fetch_symbol_caps(session, os.environ.get("COINGECKO_KEY") or None,
                                          os.environ.get("COINMARKETCAP_KEY") or None)
    except Exception as e:
        errors["종목별 시가총액"] = str(e)
        log.error("종목별 시가총액 수집 실패: %s", e)
    candles = {}
    for ex in ("upbit", "bithumb"):
        try:
            candles[ex] = C.fetch_btc_candles(session, ex, 30)
        except Exception as e:
            log.warning("%s BTC 일봉 실패: %s", ex, e)
            candles[ex] = []
    day, year = {}, {}
    for sym, _name in C.COIN_SET:
        for store, unit, count in ((day, "30m", 48), (year, "1w", 53)):
            try:
                store[sym] = C.fetch_upbit_candles(session, sym, unit, count)
            except Exception as e:
                store[sym] = []
                errors[f"{sym} {unit} 캔들"] = str(e)
                log.warning("업비트 %s %s 캔들 실패: %s", sym, unit, e)
            time.sleep(0.2)  # 업비트 공개 API 호출 제한 여유
    return dict(tickers=tickers, errors=errors, global_=global_, btc_usd=btc_usd,
                usdkrw=usdkrw, fx_source=fx_src, btc_candles=candles, coin_day=day, coin_year=year,
                symbol_caps=symbol_caps)


def _sample_candles(rnd: random.Random, end: datetime, base: float, n: int, step: timedelta, drift: float) -> list[dict]:
    out, v = [], base / (1 + drift)
    for i in range(n):
        o = v
        v = v * (1 + drift / n) * (1 + rnd.uniform(-0.006, 0.006) * (4 if step.days >= 1 else 1))
        t = end - step * (n - 1 - i)
        out.append({"t": t.strftime("%Y-%m-%dT%H:%M"), "open": o, "high": max(o, v) * 1.003,
                    "low": min(o, v) * 0.997, "close": v})
    return out


def sample_inputs(as_of: datetime) -> dict:
    """네트워크 없이 PDF 모양을 확인하기 위한 가짜 데이터(고정 시드)."""
    rnd = random.Random(20261109)
    base = {"BTC": 142_000_000, "ETH": 5_230_000, "XRP": 3_410, "SOL": 281_000, "DOGE": 412,
            "ADA": 1_210, "USDT": 1_404, "SUI": 5_120, "TRX": 410, "LINK": 31_500, "AVAX": 46_800,
            "PEPE": 0.0221, "ONDO": 1_580, "SHIB": 0.0312, "HBAR": 402, "XLM": 590, "ENA": 980}
    weight = {"upbit": 0.62, "bithumb": 0.28, "coinone": 0.06, "korbit": 0.025, "gopax": 0.015}
    vol_share = {"BTC": 0.19, "XRP": 0.16, "ETH": 0.09, "SOL": 0.06, "DOGE": 0.05, "USDT": 0.05}
    total = 4.8e12
    tickers = {}
    for ex, w in weight.items():
        ts = []
        extra = rnd.randint(20, 60 if ex in ("coinone", "korbit", "gopax") else 180)
        syms = list(base) + [f"ALT{i:03d}" for i in range(extra)]
        for sym in syms:
            p = base.get(sym, rnd.uniform(10, 5000)) * (1 + rnd.uniform(-0.004, 0.004))
            share = vol_share.get(sym, rnd.uniform(0.0005, 0.012))
            ts.append(C.Ticker(ex, sym, p, rnd.uniform(-0.09, 0.14), total * w * share * rnd.uniform(0.8, 1.2),
                               ex in ("korbit", "gopax")))
        tickers[ex] = ts
    days = [(as_of - timedelta(days=i)).strftime("%Y-%m-%d") for i in range(30, 0, -1)]
    price, up, bt = 128_000_000, [], []
    for dt in days:
        price *= 1 + rnd.uniform(-0.025, 0.03)
        up.append({"date": dt, "close": price, "value": rnd.uniform(4.5e11, 1.1e12)})
        bt.append({"date": dt, "close": price * 1.001, "value": rnd.uniform(1.5e11, 4e11)})
    g = {"btc_dom_mcap": 0.589, "total_mcap_usd": 3.62e12, "total_volume_usd": 1.38e11,
         "btc_volume_usd": 5.26e10, "btc_price_usd_cg": 98_100, "btc_mcap_usd": 2.13e12}
    g["btc_dom_volume"] = g["btc_volume_usd"] / g["total_volume_usd"]
    drift = {"BTC": 0.52, "ETH": 0.31, "XRP": 1.18, "SOL": 0.09, "DOGE": -0.06}
    day = {sym: _sample_candles(rnd, as_of - timedelta(minutes=30), base[sym], 48, timedelta(minutes=30), drift[sym] / 40)
           for sym, _ in C.COIN_SET}
    year = {sym: _sample_candles(rnd, as_of - timedelta(days=7), base[sym], 53, timedelta(days=7), drift[sym])
            for sym, _ in C.COIN_SET}
    return dict(tickers=tickers, errors={}, global_=g, btc_usd=98_120.0, usdkrw=1_402.5,
                fx_source="샘플", btc_candles={"upbit": up, "bithumb": bt}, coin_day=day, coin_year=year,
                symbol_caps={"BTC": {"mcap": 2.13e12, "price": 98_100.0}, "ETH": {"mcap": 4.3e11, "price": 3_700.0},
                             "XRP": {"mcap": 1.5e11, "price": 2.4}, "SOL": {"mcap": 9e10, "price": 190.0},
                             "DOGE": {"mcap": 5e10, "price": 0.29}, "ADA": {"mcap": 3e10, "price": 0.85},
                             "USDT": {"mcap": 1.8e11, "price": 1.0}, "USDC": {"mcap": 7e10, "price": 1.0}})


def sample_history(as_of: datetime) -> list[dict]:
    rnd = random.Random(7)
    out = []
    for i in range(30, 0, -1):
        dt = (as_of - timedelta(days=i)).strftime("%Y-%m-%d")
        out.append({"date": dt, "total_value_24h": rnd.uniform(3.2e12, 5.6e12),
                    "btc_dominance_kr": 0.17 + rnd.uniform(-0.03, 0.04),
                    "btc_dom_global_volume": 0.37 + rnd.uniform(-0.04, 0.04),
                    "btc_dom_global_mcap": 0.585 + rnd.uniform(-0.01, 0.01),
                    "btc_price_krw": 1.3e8 + rnd.uniform(-8e6, 1.2e7),
                    "kimchi_premium": 0.02 + rnd.uniform(-0.01, 0.012), "by_exchange": {},
                    "total_mcap_usd": 3.5e12 + rnd.uniform(-1.5e11, 1.5e11),
                    "exchanges": {ex: {"markets": m + rnd.randint(-1, 1), "value": v * rnd.uniform(0.7, 1.3),
                                       "prices": {"BTC": 1.3e8 + rnd.uniform(-8e6, 1.2e7),
                                                  "ETH": 5.0e6 + rnd.uniform(-3e5, 3e5),
                                                  "XRP": 3300 + rnd.uniform(-200, 200),
                                                  "SOL": 270000 + rnd.uniform(-2e4, 2e4)},
                                       "listed": rnd.choice([0, 0, 0, 1]), "delisted": rnd.choice([0, 0, 0, 0, 1])}
                                  for ex, m, v in (("upbit", 180, 3.0e12), ("bithumb", 170, 1.3e12),
                                                   ("coinone", 60, 2.6e11), ("korbit", 45, 9e10),
                                                   ("gopax", 40, 6e10))}})
    return out


def _sample_prev_exchanges(tickers: dict[str, list[C.Ticker]]) -> dict:
    """샘플용 '전일' 목록: 몇 종목을 빼고(=신규 상장) 몇 종목을 더해(=상장 폐지) 표 모양을 보여 준다."""
    prev = {"exchanges": {}}
    for i, (ex, ts) in enumerate(tickers.items()):
        syms = sorted({t.symbol for t in ts})
        alts = [x for x in syms if x.startswith("ALT")]
        removed = set(alts[-(i % 3 + 1):]) if ex != "korbit" else set()
        added = {f"OLD{i}A"} if ex in ("upbit", "bithumb") else set()
        prev["exchanges"][ex] = {"symbols": sorted((set(syms) - removed) | added)}
    return prev


# ---------------------------------------------------------------- 거래소별 스냅샷

def exchange_snapshot(date: str, tickers: dict[str, list[C.Ticker]], prev: dict | None) -> dict:
    """거래소별 상장 종목 목록·주요 코인 가격. 전일 파일과 비교해 신규 상장·상장 폐지를 뽑는다."""
    out = {"date": date, "exchanges": {}}
    for ex in C.EXCHANGES:
        ts = tickers.get(ex)
        if ts is None:
            continue
        syms = sorted({t.symbol for t in ts})
        by = {t.symbol: t for t in ts}
        prev_syms = set(((prev or {}).get("exchanges", {}).get(ex) or {}).get("symbols", []))
        out["exchanges"][ex] = {
            "markets": len(syms),
            "value_24h": sum(t.value_24h for t in ts),
            "majors": {m: {"price": by[m].price, "change_rate": by[m].change_rate,
                           "value_24h": by[m].value_24h} for m in MAJORS if m in by},
            "symbols": syms,
            # 전일 파일이 없으면 비교하지 않는다(첫날 전 종목이 '신규'로 잡히는 것 방지)
            "listed": sorted(set(syms) - prev_syms) if prev_syms else [],
            "delisted": sorted(prev_syms - set(syms)) if prev_syms else [],
        }
    return out


# ---------------------------------------------------------------- 메인

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--sample", action="store_true", help="네트워크 없이 샘플 데이터로 생성")
    ap.add_argument("--date", help="기준일(YYYY-MM-DD, 기본: 실행 시각으로 자동 판정)")
    ap.add_argument("--slot", choices=SLOTS, help="집계 슬롯(1200 또는 2400, 기본: 실행 시각으로 자동 판정)")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")

    now = datetime.now(KST)
    auto_date, auto_slot = resolve_slot(now)
    date = args.date or auto_date
    slot = args.slot or auto_slot
    when = slot_time(date, slot)          # 집계 기준 시각(24:00 은 다음 날 00:00)
    ymd = date.replace("-", "")
    key = slot_key(date, slot)
    out: Path = args.out
    log.info("집계 %s %s", date, SLOT_LABEL[slot])

    if args.sample:
        inp = sample_inputs(when)
        history = sample_history(when)
    else:
        with requests.Session() as s:
            inp = collect(s)
        history = _read_json(out / "data" / "history.json", [])

    if len(inp["tickers"]) < 3:
        log.error("거래소 5곳 중 %d곳만 수집되어 일보를 만들지 않습니다: %s", len(inp["tickers"]), inp["errors"])
        return 2

    collected_at = now.strftime("%Y-%m-%d %H:%M KST")
    snap = build_snapshot(
        as_of=when.isoformat(), collected_at=collected_at, date=date, slot=slot,
        tickers_by_ex=inp["tickers"], global_=inp["global_"], btc_usd=inp["btc_usd"],
        usdkrw=inp["usdkrw"], fx_source=inp["fx_source"], btc_candles=inp["btc_candles"],
        errors=inp["errors"], symbol_caps=inp.get("symbol_caps"))
    prev = previous(history, date, slot)

    # 거래소별 스냅샷 + 상장/상폐(직전 집계 목록과 비교)
    ex_dir = out / "data" / "exchanges"
    prev_files = sorted(p for p in ex_dir.glob("*.json") if p.stem < key) if ex_dir.exists() else []
    prev_ex = _read_json(prev_files[-1], None) if prev_files else None
    if args.sample and prev_ex is None:
        prev_ex = _sample_prev_exchanges(inp["tickers"])
    ex_snap = exchange_snapshot(date, inp["tickers"], prev_ex)
    ex_snap["slot"] = slot
    snap["listings"] = {ex: {"listed": v["listed"], "delisted": v["delisted"]}
                        for ex, v in ex_snap["exchanges"].items()}

    entry = history_entry(snap)
    # 거래소별 시계열(종목 수·거래대금·주요 코인 가격·상장/상폐 건수) — 웹·앱 '거래소별' 화면용
    entry["exchanges"] = {ex: {"markets": v["markets"], "value": v["value_24h"],
                               "prices": {m: p["price"] for m, p in v["majors"].items()},
                               "values": {m: p["value_24h"] for m, p in snap["exchange_majors"].get(ex, {}).items()},
                               "listed": len(v["listed"]), "delisted": len(v["delisted"])}
                          for ex, v in ex_snap["exchanges"].items()}
    history = merge_history(history, entry)

    # 업비트 주요 코인 시세(당일·1년 그래프)
    coins = build_coins(as_of=snap["as_of"], collected_at=collected_at, date=date, slot=slot,
                        upbit_tickers=inp["tickers"].get("upbit", []),
                        day=inp.get("coin_day", {}), year=inp.get("coin_year", {}), coin_set=C.COIN_SET)

    rep_dir = out / "reports" / ymd[:4]
    pdf = render_pdf(snap, history, prev, rep_dir / f"{key}.pdf", sample=args.sample)
    shutil.copyfile(pdf, out / "reports" / "latest.pdf")
    hwpx = None
    try:  # HWPX 는 부가 형식이라 실패해도 PDF·데이터 갱신은 계속한다
        hwpx = render_hwpx(snap, history, prev, rep_dir / f"{key}.hwpx", sample=args.sample)
        shutil.copyfile(hwpx, out / "reports" / "latest.hwpx")
    except Exception:
        log.exception("HWPX 생성 실패(PDF 는 정상)")

    _write_json(out / "data" / "latest.json", snap)
    _write_json(out / "data" / "daily" / f"{key}.json", snap)
    _write_json(out / "data" / "history.json", history)
    _write_json(out / "data" / "coins.json", coins)
    _write_json(ex_dir / f"{key}.json", ex_snap)
    index = _read_json(out / "reports" / "index.json", [])
    index = [r for r in index if not (r.get("date") == date and r.get("slot", "") == slot)]
    item = {"date": date, "slot": slot, "slot_label": SLOT_LABEL[slot],
            "pdf": f"reports/{ymd[:4]}/{key}.pdf", "json": f"data/daily/{key}.json"}
    if hwpx:
        item["hwpx"] = f"reports/{ymd[:4]}/{key}.hwpx"
    index.append(item)
    index.sort(key=lambda r: (r["date"], r.get("slot", "")), reverse=True)
    _write_json(out / "reports" / "index.json", index)
    log.info("완료: %s", pdf)
    return 0


if __name__ == "__main__":
    sys.exit(main())
