// 임시 점검: FISIS에서 회사별 펀드 순자산(공모/사모) 통계표가 있는지 확인한다. 확인 뒤 삭제한다.
import { fisisFetch, rowsOf, fetchTable } from "../news-proxy/fisis-lib.js";
const key = (process.env.FISIS_API_KEY || "").trim();
const call = (n, p) => fisisFetch(key, n, p);
for (const div of ["G", "F"]) {
  const lists = rowsOf((await call("statisticsListSearch", { lrgDiv: div })).list);
  const hit = lists.filter((x) => /펀드|수탁|설정|순자산/.test(`${x.sml_div_nm}${x.list_nm}`));
  console.log(`== ${div}: 통계표 ${lists.length}개, 펀드 관련 ${hit.length}개`);
  const cs = rowsOf((await call("companySearch", { partDiv: div })).list);
  console.log(`회사 ${cs.length}곳 예: ${cs.slice(0, 5).map((c) => c.finance_nm).join(", ")}`);
  for (const t of hit.slice(0, 8)) {
    console.log(`- ${t.list_no} [${t.sml_div_nm}] ${t.list_nm}`);
    try {
      const ac = rowsOf((await call("accountListSearch", { listNo: t.list_no })).list);
      console.log(`  계정 ${ac.length}개: ${ac.slice(0, 12).map((a) => a.account_nm).join(" | ")}`);
      const co = cs.find((c) => /미래에셋|한국투자/.test(c.finance_nm)) || cs[0];
      const tb = await fetchTable((p) => call("statisticsInfoSearch", p), co.finance_cd, t.list_no, "Q");
      if (tb) {
        console.log(`  표본 ${co.finance_nm} term=${tb.term} unit=${tb.unit} 열=${tb.columns.map((c) => c.name).join(",")}`);
        for (const k of tb.order.slice(0, 6)) {
          const a = tb.accounts[k];
          const last = a.points[a.points.length - 1];
          console.log(`   ${a.name}: ${last.month} ${JSON.stringify(last.raw)}`);
        }
      } else console.log("  표본 자료 없음");
    } catch (e) {
      console.log("  오류", e.message);
    }
  }
}
