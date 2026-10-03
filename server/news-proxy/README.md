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
