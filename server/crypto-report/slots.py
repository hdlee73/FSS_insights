"""집계 시각(슬롯). 한국시간 매일 24:00(자정) 마감 기준으로 하루 한 번 모은다. 12:00 슬롯은 옛 기록을 읽기 위해서만 남겨 두었다.

24:00 집계는 날짜가 바뀐 직후(00:00)에 실행되지만 '끝난 날'의 마감 값이므로 그날 날짜로 적는다.
  실행 11/10 12:03 KST → (2026-11-10, 1200)
  실행 11/11 00:03 KST → (2026-11-10, 2400)
GitHub 예약 실행은 늦을 수 있어, 시각이 아니라 '가장 최근에 지난 12시간 경계'로 슬롯을 정한다.
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
SLOTS = ("1200", "2400")
SLOT_LABEL = {"1200": "12:00", "2400": "24:00"}


def resolve_slot(now: datetime) -> tuple[str, str]:
    """실행 시각 → (날짜 YYYY-MM-DD, 슬롯). 하루 한 번 24:00 마감 기준으로만 집계한다
    (자정 직후 실행되면 방금 끝난 날, 낮에 수동 실행해도 가장 최근에 끝난 날의 24:00 로 적는다)."""
    k = now.astimezone(KST)
    return (k - timedelta(days=1)).strftime("%Y-%m-%d"), "2400"


def slot_time(date: str, slot: str) -> datetime:
    """집계 기준 시각(KST). 24:00 은 다음 날 00:00."""
    base = datetime.strptime(date, "%Y-%m-%d").replace(tzinfo=KST)
    return base + timedelta(hours=24 if slot == "2400" else 12)


def slot_key(date: str, slot: str) -> str:
    """파일 이름에 쓰는 키. 예: 20261110-1200"""
    return f"{date.replace('-', '')}-{slot}"


def slot_text(date: str, slot: str) -> str:
    """사람이 읽는 표기. 예: 2026-11-10 12:00"""
    return f"{date} {SLOT_LABEL.get(slot, '')}".strip()
