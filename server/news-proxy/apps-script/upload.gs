// FSS Insights 자료 올리기용 Apps Script 웹앱.
// 내 계정으로 실행되어 "업로드 대기" 폴더에 파일을 저장한다(서비스 계정은 저장 용량이 없어 개인 드라이브에 올릴 수 없음).
// 스크립트 속성(프로젝트 설정 → 스크립트 속성)에 SECRET, FOLDER_ID를 넣어 둔다.
function doPost(e) {
  try {
    const props = PropertiesService.getScriptProperties();
    const req = JSON.parse(e.postData.contents);
    if (!req.secret || req.secret !== props.getProperty("SECRET")) return reply({ ok: false, error: "unauthorized" });
    const blob = Utilities.newBlob(Utilities.base64Decode(req.data), req.mimeType || "application/octet-stream", req.name);
    const file = DriveApp.getFolderById(props.getProperty("FOLDER_ID")).createFile(blob);
    if (req.description) file.setDescription(req.description);
    return reply({ ok: true });
  } catch (err) {
    return reply({ ok: false, error: String(err).slice(0, 200) });
  }
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
