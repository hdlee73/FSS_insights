# 뉴스 프록시 (네이버 키를 서버 뒤에 두기)

앱은 이 서버에만 요청하고, 네이버 키는 서버 비밀값으로만 보관합니다. 앱·APK·GitHub 소스에는 키가 들어가지 않습니다.

## 한 번만 설정
GitHub 저장소 → Settings → Secrets and variables → Actions 에서:

| 종류 | 이름 | 값 |
|---|---|---|
| Secret | `CLOUDFLARE_API_TOKEN` | Cloudflare API 토큰(Edit Cloudflare Workers 템플릿) |
| Secret | `CLOUDFLARE_ACCOUNT_ID` | Cloudflare 계정 ID |
| Secret | `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | 네이버 개발자센터 키 |
| Secret | `NEWS_PROXY_TOKEN` | 앱과 서버가 공유하는 임의의 긴 문자열(직접 만들어 입력, 예: 영문·숫자 40자) |

1. Actions → **Deploy news proxy** → Run workflow. 실행 요약(Summary)에 `NEWS_PROXY_URL`이 표시됩니다.
2. 그 주소를 Variables에 `NEWS_PROXY_URL` 이름으로 저장합니다.
3. 앱을 다시 빌드(Android build)하면 네이버 검색이 기본으로 동작합니다. 서버가 없으면 앱은 Google 뉴스로 대신 검색합니다.

## 참고
- 앱 토큰은 APK 안에 들어 있어 완전한 비밀은 아닙니다. 네이버 API의 일일 호출 한도(25,000건)가 남용의 상한이며, 필요하면 토큰을 바꿔 다시 배포하세요.
- 같은 검색어는 2분간 서버 캐시를 사용합니다.

## 자료실(구글 드라이브) 연결 (v0.9.0)
드라이브에 올린 참고자료를 앱의 **자료실** 탭에서 목록으로 보고 내려받게 합니다. 파일은 드라이브에 그대로 두고, 서버는 읽기 전용으로 중계만 합니다.

1. 드라이브에 자료실용 폴더를 만들고(하위 폴더 가능) **공유 → 일반 액세스 → "링크가 있는 모든 사용자(뷰어)"** 로 설정합니다. 파일은 이 폴더 안에 올리면 됩니다.
2. 폴더 주소(`drive.google.com/drive/folders/`**여기부분**)의 뒷부분이 폴더 ID입니다.
3. [Google Cloud 콘솔](https://console.cloud.google.com) → 프로젝트 만들기 → *API 및 서비스* → **Google Drive API 사용** → *사용자 인증 정보* → **API 키** 만들기. (키 제한에서 API를 Drive API로만 제한하는 것을 권장)
4. GitHub Secrets에 추가: `GOOGLE_API_KEY`(위 API 키), `GDRIVE_FOLDER_ID`(폴더 ID).
5. Actions → **Deploy news proxy** 다시 실행.

### 제목·설명·태그 붙이기
- **제목** = 드라이브 파일 이름, **설명** = 파일 우클릭 → 세부정보(ⓘ) → 활동/세부정보의 *설명*, **태그** = 설명 안에 `#검사 #자산운용`처럼 적은 해시태그입니다. 앱의 검색창은 이름·설명·태그를 모두 찾고, `#태그`로 검색하면 그 태그가 달린 파일만 나옵니다.
- 새 파일은 1분 안에 앱에 반영됩니다(서버가 1분간 목록을 기억).

### 운영 메모
- 폴더에 올린 파일은 링크를 아는 누구나 볼 수 있는 상태가 됩니다. 비공개 자료는 올리지 마세요.
- 서버는 지정한 폴더와 그 하위 폴더의 파일만 내줍니다. 구글 문서·슬라이드는 PDF, 스프레드시트는 엑셀로 변환되어 내려갑니다.
