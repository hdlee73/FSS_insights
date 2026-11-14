// node server/news-proxy/usage-lib.test.mjs  — 방문·사용 시간 집계 검증
import assert from "node:assert/strict";
import { sanitizePing, engagement } from "./usage-lib.js";

const now = Date.parse("2026-11-13T03:00:00Z");
const id = "a".repeat(20);

// 1) 옛 클라이언트(vis/sec 없음)도 그대로 받아들인다
let p = sanitizePing({ id, v: "2.6.0", days: [{ d: "2026-11-13", m: { "뉴스 검색": 2 } }] }, now);
assert.equal(p.days[0].visits, null);
assert.equal(p.days[0].secs, null);
assert.equal(p.days[0].menus["뉴스 검색"], 2);

// 2) 새 필드: 정수·상한 보정, 이상한 값은 0
p = sanitizePing({ id, v: "web-2.20.0", days: [{ d: "2026-11-13", m: {}, vis: 3.9, sec: 99999999 }, { d: "2026-11-12", m: {}, vis: "x", sec: -5 }] }, now);
assert.equal(p.days[0].visits, 3);
assert.equal(p.days[0].secs, 86400);
assert.equal(p.days[1].visits, 0);
assert.equal(p.days[1].secs, 0);

// 3) 집계
const allRows = [
  { day: "2026-11-12", uid: "u1", version: "2.7.0" },
  { day: "2026-11-12", uid: "u2", version: "web-2.20.0" },
  { day: "2026-11-12", uid: "u3", version: "web-2.19.0" }, // 옛 웹: 측정 없음
  { day: "2026-11-13", uid: "u1", version: "2.7.0" },
];
const activityRows = [
  { day: "2026-11-12", uid: "u1", visits: 2, secs: 600, last_at: 1763000000000 },
  { day: "2026-11-12", uid: "u2", visits: 6, secs: 1800 },
  { day: "2026-11-13", uid: "u1", visits: 4, secs: 120 },
];
let e = engagement({ allRows, activityRows, from: "2026-11-11", to: "2026-11-13" });
assert.equal(e.userDays, 3);
assert.equal(e.avgVisits, 4);
assert.equal(e.maxVisits, 6);
assert.equal(e.avgSecs, 840);
assert.equal(e.maxSecs, 1800);
assert.equal(e.firstDay, "2026-11-12");
const d12 = e.series.find((x) => x.day === "2026-11-12");
assert.deepEqual([d12.dau, d12.users, d12.avgVisits, d12.maxVisits], [3, 2, 4, 6]);
assert.equal(e.series.find((x) => x.day === "2026-11-11").avgVisits, null);
assert.deepEqual(d12.list.map((x) => [x.id, x.p, x.visits, x.secs]), [["u2", "web", 6, 1800], ["u1", "app", 2, 600]]); // 사용 시간 긴 순
assert.deepEqual(d12.list.map((x) => x.at), [null, 1763000000000]);
assert.deepEqual(d12.list.map((x) => [x.cd, x.cv, x.cs]), [[1, 6, 1800], [2, 6, 720]]); // u1: 11/12(2회·600초)+11/13(4회·120초), u2: 11/12 하루
assert.equal(e.series.find((x) => x.day === "2026-11-11").list.length, 0);
assert.equal(e.visitDist["2~3회"], 1);
assert.equal(e.timeDist["15~30분"], 1);
assert.equal(e.timeDist["1~5분"], 1);

e = engagement({ allRows, activityRows, from: "2026-11-11", to: "2026-11-13", ufrom: "2000-01-01", uto: "2026-11-13" });
assert.deepEqual(e.period.users.map((x) => [x.id, x.days, x.visits, x.secs]), [["u2", 1, 6, 1800], ["u1", 2, 6, 720]]);
assert.equal(e.period.from, "2026-11-12");
e = engagement({ allRows, activityRows, from: "2026-11-11", to: "2026-11-13", ufrom: "2026-11-13", uto: "2026-11-13" });
assert.deepEqual(e.period.users.map((x) => [x.id, x.days, x.visits, x.secs]), [["u1", 1, 4, 120]]);

// 4) 플랫폼 필터
e = engagement({ allRows, activityRows, from: "2026-11-11", to: "2026-11-13", platform: "web" });
assert.equal(e.userDays, 1);
assert.equal(e.avgVisits, 6);
e = engagement({ allRows, activityRows, from: "2026-11-11", to: "2026-11-13", platform: "app" });
assert.equal(e.userDays, 2);
assert.equal(e.avgVisits, 3);

// 5) 자료 없음
e = engagement({ allRows, activityRows: [], from: "2026-11-11", to: "2026-11-13" });
assert.equal(e.avgVisits, null);
assert.equal(e.firstDay, "");
console.log("usage-lib engagement tests passed");
