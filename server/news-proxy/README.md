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

## 게시판("감독·검사 팁") 추가 설정 (v0.8.0)
게시판은 같은 Worker에 D1(글·댓글)과 R2(첨부 파일)를 붙여 씁니다. 처음 한 번만:

1. Cloudflare 대시보드 → **R2 Object Storage** → 활성화(무료 한도 안에서 사용, 결제 수단 등록을 요구할 수 있음).
2. Cloudflare → My Profile → API Tokens에서 `CLOUDFLARE_API_TOKEN`의 권한에 **Account · D1 · Edit** 과 **Account · Workers R2 Storage · Edit** 를 추가(또는 새 토큰을 만들어 Secret 교체).
3. Actions → **Deploy news proxy** → Run workflow. D1 데이터베이스(`inspector-tips`)와 R2 버킷(`inspector-tips-files`)은 배포 때 자동으로 만들어지고 표 구조도 서버가 처음 요청을 받을 때 만듭니다.
4. 앱을 새로 빌드하면 게시판 탭이 동작합니다. 서버를 배포하기 전에는 게시판 탭에 "서버가 연결되지 않았습니다"가 보입니다.

### 운영 메모
- 글쓴이는 기기별 익명 식별값과 별명으로만 구분됩니다. 로그인은 없고, 내 글·댓글만 지울 수 있습니다.
- 앱 토큰이 APK에 들어 있어 APK를 가진 누구나 서버를 호출할 수 있습니다. 기기당 시간당 글 10개·댓글 40개·파일 30개로 제한하고, 신고 3건이면 글을 가립니다.
- 글 목록 직접 정리가 필요하면 Cloudflare 대시보드 → D1 → `inspector-tips` 콘솔에서 `DELETE FROM posts WHERE id = …` 로 지울 수 있습니다(첨부는 R2에서 별도 삭제).
