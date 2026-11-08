package io.github.hdlee73.financenewsradar.accounting.data

import org.json.JSONArray
import org.json.JSONObject

/** 수집기(server/accounting-data)가 만든 JSON을 모델로 바꾼다. 모르는 필드는 무시하고 빠진 값은 기본값을 쓴다. */
object DataParser {
    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

    private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

    private fun files(a: JSONArray?): List<FileLink> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull {
            val o = a.optJSONObject(it) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.isEmpty()) null else FileLink(o.optString("name"), url)
        }

    fun parseAudit(json: String): Dataset<AuditAction> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("items") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val f = o.optJSONObject("flags") ?: JSONObject()
            val fa = o.optJSONArray("findings") ?: JSONArray()
            AuditAction(
                slno = o.optString("slno"),
                company = o.optString("company"),
                auditPeriod = o.optString("auditPeriod"),
                actionDate = o.optString("actionDate"),
                market = o.optString("market"),
                viewUrl = o.optString("viewUrl"),
                files = files(o.optJSONArray("files")),
                decisionRound = o.optJSONObject("decision")?.intOrNull("round"),
                fiscalYearEnds = o.optString("fiscalYearEnds"),
                industry = o.optString("industry"),
                findings = (0 until fa.length()).mapNotNull { j ->
                    val x = fa.optJSONObject(j) ?: return@mapNotNull null
                    Finding(
                        no = x.optInt("no"), title = x.optString("title"), amountWon = x.longOrNull("amountWon"),
                        amountNote = x.optString("amountNote"), summary = x.optString("summary"),
                        accounts = x.optJSONArray("accounts").strings(), violations = x.optJSONArray("violations").strings()
                    )
                },
                actions = o.optJSONArray("actions").strings(),
                flags = AuditFlags(
                    fine = f.optBoolean("fine"), fineWon = f.longOrNull("fineWon"), fineFinal = f.optBoolean("fineFinal"),
                    fineNote = f.optString("fineNote"), auditorDesignationYears = f.intOrNull("auditorDesignationYears"),
                    issuanceLimitMonths = f.intOrNull("issuanceLimitMonths"), prosecution = f.optBoolean("prosecution"),
                    referral = f.optBoolean("referral"), correction = f.optBoolean("correction"),
                    dismissalRec = f.optBoolean("dismissalRec"), dutySuspension = f.optBoolean("dutySuspension"),
                    auditorRestriction = f.optBoolean("auditorRestriction")
                ),
                violationTypes = o.optJSONArray("violationTypes").strings(),
                accounts = o.optJSONArray("accounts").strings(),
                parseStatus = o.optString("parseStatus", "failed")
            )
        }
        return Dataset(root.optString("generatedAt"), list.distinctBy { it.key }.sortedByDescending { it.actionDate })
    }

    fun parseDisclosure(json: String): Dataset<DisclosureCase> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("items") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isEmpty() || o.optString("company").isEmpty()) return@mapNotNull null
            val va = o.optJSONArray("violations") ?: JSONArray()
            DisclosureCase(
                id = id, company = o.optString("company"), role = o.optString("role"), masked = o.optBoolean("masked"),
                date = o.optString("date"), postDate = o.optString("postDate"), round = o.optString("round"),
                committee = o.optString("committee"), actions = o.optJSONArray("actions").strings(),
                kinds = o.optJSONArray("kinds").strings(), fineWon = o.longOrNull("fineWon"),
                issueLimitMonths = o.intOrNull("issueLimitMonths"),
                violations = (0 until va.length()).mapNotNull { j ->
                    val v = va.optJSONObject(j) ?: return@mapNotNull null
                    DisclosureViolation(v.optString("title"), v.optJSONArray("facts").strings(), v.optJSONArray("laws").strings())
                },
                laws = o.optJSONArray("laws").strings(), lawRefs = o.optJSONArray("lawRefs").strings(), coCount = o.optInt("coCount", 1),
                postUrl = o.optString("postUrl"), fileName = o.optString("fileName"), fileUrl = o.optString("fileUrl")
            )
        }
        return Dataset(root.optString("generatedAt"), list.distinctBy { it.id }.sortedWith(compareByDescending<DisclosureCase> { it.date.ifEmpty { it.postDate } }.thenByDescending { it.id }))
    }

    fun parseBoard(json: String): Dataset<BoardItem> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("items") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.isEmpty()) return@mapNotNull null
            BoardItem(
                id = o.optString("id"), title = o.optString("title"), date = o.optString("date"),
                meta = o.optJSONArray("meta").strings(), url = url, kind = o.optString("kind"),
                kindLabel = o.optString("kindLabel"), files = files(o.optJSONArray("files")), source = o.optString("source")
            )
        }
        return Dataset(root.optString("generatedAt"), list.distinctBy { it.kind + "-" + it.id }.sortedByDescending { it.date })
    }

    fun parseStandards(json: String): Dataset<StandardItem> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("items") ?: JSONArray()
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url")
            if (url.isEmpty()) return@mapNotNull null
            StandardItem(o.optString("group"), o.optString("no"), o.optString("label"), o.optString("title"), url)
        }
        return Dataset(root.optString("generatedAt"), list)
    }
}
