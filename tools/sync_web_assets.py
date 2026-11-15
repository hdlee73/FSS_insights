#!/usr/bin/env python3
"""웹(fss-insights-web)의 소비자 지킴이·제재 양정 시뮬레이터·가상자산 시장 동향 화면을 앱 assets/web 으로 복사한다.

앱은 이 화면들을 WebView(앱에 내장된 파일, 오프라인에서도 열림)로 보여 주므로 웹과 같은 코드를 쓴다.
웹에서 이 화면들을 고치면 아래처럼 다시 실행하면 앱에도 반영된다.

    python3 tools/sync_web_assets.py ../fss-insights-web

앱 전용 부분: assets/web/index.html, assets/web/js/app.js(이 스크립트가 만들지 않음, 손으로 관리)와
core.js 의 네이티브 연결(openUrl·download·share → 앱의 FssNative)만 다르다.
"""
import os, re, shutil, sys

SRC = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "../fss-insights-web")
DST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "web")
DST = os.path.abspath(DST)
ENTRY = ["js/screens/consumer.js", "js/screens/crypto.js", "js/screens/finsim.js", "js/screens/risk-view.js"]
# 웹 v2.17.1부터 리스크 대시보드는 금융회사 주요 현황(fisis.js) 안으로 통합되어 단독 화면(risk.js)이 없다.
# 앱은 FISIS 화면이 네이티브라 화면 본체(risk-view.js)와 자료 공급원(risk-live.js)만 쓰고, 얇은 진입 화면을 이 스크립트가 만든다.
RISK_SHIM = '''// (앱 전용·자동 생성) 금융사 리스크 대시보드 진입 화면. 화면 본체는 risk-view.js.
import { setHeader } from "../app.js";
import { openUrl } from "../core.js";
import { liveProvider } from "../lib/risk-live.js";
import { mountRisk } from "./risk-view.js";

export async function render(root, params, ctx) {
  return mountRisk(root, params, { provider: liveProvider(), setHeader, go: (p) => ctx.go(p), openUrl });
}
'''

def closure():
    seen, todo = set(), list(ENTRY)
    while todo:
        f = todo.pop()
        if f in seen or f == "js/app.js":
            continue
        seen.add(f)
        s = open(os.path.join(SRC, f), encoding="utf-8").read()
        if f == "js/screens/risk-view.js":
            todo.append("js/lib/risk-live.js")
        for m in re.finditer(r'(?:from\s*|import\(\s*)["\'`](\.[^"\'`]+)["\'`]', s):
            p = os.path.normpath(os.path.join(os.path.dirname(f), m.group(1)))
            if os.path.exists(os.path.join(SRC, p)):
                todo.append(p)
    return sorted(seen)

def patch_core(s):
    def sub(old, new):
        nonlocal s
        if old not in s:
            sys.exit("core.js 패턴을 찾지 못했습니다(웹 core.js가 바뀐 듯합니다): " + old[:60])
        s = s.replace(old, new, 1)
    sub('export function openUrl(url) {\n', 'export function openUrl(url) {\n  if (window.FssNative) return window.FssNative.openUrl(String(url));\n')
    sub('export async function share(title, url) {\n', 'export async function share(title, url) {\n  if (window.FssNative) return window.FssNative.share(String(title), String(url));\n')
    sub('export function download(blob, name, opt = {}) {\n', '''export function download(blob, name, opt = {}) {
  if (window.FssNative) {
    // 앱: 파일 저장 창(SAF)으로 넘긴다.
    const fr = new FileReader();
    fr.onload = () => window.FssNative.saveFile(String(name), blob.type || "application/octet-stream", String(fr.result).split(",")[1] || "");
    fr.readAsDataURL(blob);
    return;
  }
''')
    return s

def patch_report(s):
    old = 'export function printReport(doc, base) {\n'
    if old not in s:
        sys.exit("report.js 패턴을 찾지 못했습니다(웹 report.js가 바뀐 듯합니다): " + old[:60])
    # 앱 WebView는 인쇄(PDF) 창을 지원하지 않으므로 한글(HWPX) 저장으로 안내한다.
    return s.replace(old, old + '  if (window.FssNative) { toast("앱에서는 PDF 저장을 지원하지 않아요. 한글(HWPX)로 저장해 주세요."); return; }\n', 1)

def patch_crypto(s):
    def sub(old, new):
        nonlocal s
        if old not in s:
            sys.exit("crypto.js 패턴을 찾지 못했습니다(웹 crypto.js가 바뀐 듯합니다): " + old[:60])
        s = s.replace(old, new, 1)
    # 일보 HTML 저장은 앱에서는 파일 저장 창으로, 인쇄(PDF) 단추는 WebView에서 동작하지 않으므로 숨긴다.
    sub('import { card, errorText, h, icon, loading, message, reportError, shareFile, tabs } from "../core.js";', 'import { card, download as coreDownload, errorText, h, icon, loading, message, reportError, shareFile, tabs } from "../core.js";')
    sub('    const a = h("a", { href: URL.createObjectURL(blob), download: `가상자산시장일보_${data.snapshot.date}.html` });\n    document.body.append(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(a.href), 4000);\n', '    coreDownload(blob, `가상자산시장일보_${data.snapshot.date}.html`);\n')
    sub('h("button", { type: "button", class: "btn ghost sm", onclick: pr }, "PDF로 저장(인쇄)")', 'window.FssNative ? null : h("button", { type: "button", class: "btn ghost sm", onclick: pr }, "PDF로 저장(인쇄)")')
    return s

files = closure()
PATCHED = {}
for _f in files:
    if _f == "js/core.js":
        PATCHED[_f] = patch_core(open(os.path.join(SRC, _f), encoding="utf-8").read())
    elif _f == "js/lib/report.js":
        PATCHED[_f] = patch_report(open(os.path.join(SRC, _f), encoding="utf-8").read())
    elif _f == "js/screens/crypto.js":
        PATCHED[_f] = patch_crypto(open(os.path.join(SRC, _f), encoding="utf-8").read())
data = [os.path.join("data/consumer", f) for f in sorted(os.listdir(os.path.join(SRC, "data/consumer")))]
for sub in ("js", "css", "data", "icons"):
    shutil.rmtree(os.path.join(DST, sub), ignore_errors=True) if sub != "js" else None
# js 는 app.js(앱 전용)를 남기고 나머지를 새로 복사한다.
keep = os.path.join(DST, "js", "app.js")
saved = open(keep, encoding="utf-8").read() if os.path.exists(keep) else None
shutil.rmtree(os.path.join(DST, "js"), ignore_errors=True)
for f in files + data + ["css/app.css", "icons/icon-192.png"]:
    out = os.path.join(DST, f)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    if f in PATCHED:
        open(out, "w", encoding="utf-8").write(PATCHED[f])
    else:
        shutil.copyfile(os.path.join(SRC, f), out)
os.makedirs(os.path.join(DST, "js", "screens"), exist_ok=True)
open(os.path.join(DST, "js", "screens", "risk.js"), "w", encoding="utf-8").write(RISK_SHIM)
if saved is not None:
    os.makedirs(os.path.dirname(keep), exist_ok=True)
    open(keep, "w", encoding="utf-8").write(saved)
print(f"{len(files)} js, {len(data)} data 복사 → {DST}")
