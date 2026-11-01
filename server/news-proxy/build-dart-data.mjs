// 배포할 때(Deploy news proxy 워크플로) DART 고유번호 목록(corpCode.xml)을 받아 dart-corps.json으로 만든다.
// DART에는 회사명으로 고유번호를 찾는 API가 없어서, 서버가 검색할 수 있게 상장사와 최근 공시 이력이 있는 비상장사만 추려 함께 올린다.
// DART_API_KEY가 없거나 실패하면 기존 파일을 그대로 둔다(검색 결과가 비어 보인다).
import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const OUT = join(dirname(fileURLToPath(import.meta.url)), "dart-corps.json");
const key = (process.env.DART_API_KEY || "").trim();
if (!key) {
  console.log("DART_API_KEY가 없어 건너뜁니다.");
  process.exit(0);
}

const SINCE = "20230101"; // 비상장사는 이 날짜 이후 정보가 바뀐(공시가 있는) 곳만 담는다.

try {
  const res = await fetch(`https://opendart.fss.or.kr/api/corpCode.xml?crtfc_key=${encodeURIComponent(key)}`);
  if (!res.ok) throw new Error(`응답 ${res.status}`);
  const zip = Buffer.from(await res.arrayBuffer());
  if (zip.subarray(0, 2).toString() !== "PK") throw new Error("zip이 아닙니다: " + zip.toString("utf8", 0, 200));
  const dir = mkdtempSync(join(tmpdir(), "dart-"));
  writeFileSync(join(dir, "c.zip"), zip);
  const xml = execFileSync("unzip", ["-p", join(dir, "c.zip")], { maxBuffer: 256 * 1024 * 1024 }).toString("utf8");
  const text = (block, tag) => {
    const m = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(block);
    return m ? m[1].trim() : "";
  };
  const decode = (s) => s.replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'");
  const corps = [];
  let total = 0;
  for (const block of xml.split("<list>").slice(1)) {
    total++;
    const code = text(block, "corp_code");
    const name = decode(text(block, "corp_name"));
    const stock = text(block, "stock_code");
    const modified = text(block, "modify_date");
    if (!code || !name) continue;
    if (stock || modified >= SINCE) corps.push([code, name, stock, stock ? "Y" : "E"]);
  }
  if (corps.length < 1000) throw new Error(`회사 수가 너무 적습니다(${corps.length}).`);
  writeFileSync(OUT, JSON.stringify({ built: new Date().toISOString().slice(0, 10), corps }));
  console.log(`DART 회사 ${total}곳 중 ${corps.length}곳을 담았습니다.`);
} catch (e) {
  console.log("DART 회사 목록을 만들지 못했습니다:", e.message);
}
