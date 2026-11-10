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
ENTRY = ["js/screens/consumer.js", "js/screens/crypto.js", "js/screens/finsim.js"]

def closure():
    seen, todo = set(), list(ENTRY)
    while todo:
        f = todo.pop()
        if f in seen or f == "js/app.js":
            continue
        seen.add(f)
        s = open(os.path.join(SRC, f), encoding="utf-8").read()
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
    sub('export function download(blob, name) {\n', '''export function download(blob, name) {
  if (window.FssNative) {
    // 앱: 파일 저장 창(SAF)으로 넘긴다.
    const fr = new FileReader();
    fr.onload = () => window.FssNative.saveFile(String(name), blob.type || "application/octet-stream", String(fr.result).split(",")[1] || "");
    fr.readAsDataURL(blob);
    return;
  }
''')
    return s

files = closure()
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
    if f == "js/core.js":
        open(out, "w", encoding="utf-8").write(patch_core(open(os.path.join(SRC, f), encoding="utf-8").read()))
    else:
        shutil.copyfile(os.path.join(SRC, f), out)
if saved is not None:
    os.makedirs(os.path.dirname(keep), exist_ok=True)
    open(keep, "w", encoding="utf-8").write(saved)
print(f"{len(files)} js, {len(data)} data 복사 → {DST}")
