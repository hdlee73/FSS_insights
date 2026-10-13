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
드라이브에 올린 참고자료를 앱의 **참고 자료실** 탭에서 목록으로 보고 내려받게 합니다. 파일은 드라이브에 그대로 두고, 서버는 읽기 전용으로 중계만 합니다.

1. 드라이브에 자료실용 폴더를 만들고(하위 폴더 가능) **공유 → 일반 액세스 → "링크가 있는 모든 사용자(뷰어)"** 로 설정합니다. 파일은 이 폴더 안에 올리면 됩니다.
2. 폴더 주소(`drive.google.com/drive/folders/`**여기부분**)의 뒷부분이 폴더 ID입니다.
3. [Google Cloud 콘솔](https://console.cloud.google.com) → 프로젝트 만들기 → *API 및 서비스* → **Google Drive API 사용** → *사용자 인증 정보* → **API 키** 만들기. (키 제한에서 API를 Drive API로만 제한하는 것을 권장)
4. GitHub Secrets에 추가: `GOOGLE_API_KEY`(위 API 키), `GDRIVE_FOLDER_ID`(폴더 ID).
5. Actions → **Deploy news proxy** 다시 실행.

### 제목·설명·태그 붙이기
- **제목** = 드라이브 파일 이름, **설명** = 파일 우클릭 → 세부정보(ⓘ) → 활동/세부정보의 *설명*, **태그** = 설명 안에 `#검사 #자산운용`처럼 적은 해시태그입니다. 앱의 검색창은 이름·설명·태그에 더해 파일 본문까지 찾고(아래 *본문 검색* 참고), `#태그`로 검색하면 그 태그가 달린 파일만 나옵니다.
- 새 파일은 1분 안에 앱에 반영됩니다(서버가 1분간 목록을 기억).

### 본문 검색
- 별도 설정 없이 동작합니다. 구글 드라이브가 PDF·구글 문서·워드·엑셀·파워포인트·텍스트(스캔 PDF·이미지는 문자 인식 포함)를 자동으로 색인해 두므로, 서버가 드라이브 `fullText` 검색을 자료실 폴더 안으로 한정해 호출합니다. 본문에서만 찾은 결과에는 앱에 「본문 일치」가 표시됩니다.
- 한계: 한글(HWP)·압축 파일은 색인되지 않고, 새로 올린 파일은 드라이브가 색인할 때까지 수 분~수 시간 걸릴 수 있으며, 단어 일부(조사 앞부분 등)만으로는 일치하지 않을 수 있습니다. 본문 검색이 실패하면 제목·설명·태그 결과만 보여 줍니다.

### 운영 메모
- 폴더에 올린 파일은 링크를 아는 누구나 볼 수 있는 상태가 됩니다. 비공개 자료는 올리지 마세요.
- 서버는 지정한 폴더와 그 하위 폴더의 파일만 내줍니다. 구글 문서·슬라이드는 PDF, 스프레드시트는 엑셀로 변환되어 내려갑니다.

## 자료 올리기 제거
앱에서 파일을 올리는 기능과 서버의 `/library/upload` 경로는 제거했습니다(참고 자료실은 조회·내려받기 전용). 파일은 구글 드라이브 폴더에 직접 올립니다. 예전에 설정한 Apps Script 웹앱 배포와 GitHub Secrets `APPS_SCRIPT_URL`·`APPS_SCRIPT_SECRET`, Cloudflare Worker의 같은 이름 비밀값은 더 쓰지 않으므로 지워도 됩니다.

## 국내 금융 통계(한국은행 ECOS)
시장동향 탭의 "국내 금융 통계"(기준금리, 국고채 3·10년, CD 91일, 회사채 AA-, 가계신용, 은행 가계대출·연체율·대출금리, 외환보유액)를 서버 `/stats`가 중계합니다.

1. [ECOS Open API](https://ecos.bok.or.kr/api/) 인증키를 발급받아 GitHub Secrets에 `ECOS_API_KEY`로 저장합니다.
2. Actions → **Deploy news proxy** 다시 실행.

통계표·항목 코드는 ECOS 통계표/항목 목록에서 확인한 값을 `STAT_SPECS`에 적어 두었습니다(기준금리·외환보유액만 이름 키워드로 찾음). 앱은 `/stats?ids=ktb-3y,corp-aa,…`처럼 사용자가 고른 통계만 요청하고, `ids`가 없으면 기본 묶음(`DEFAULT_STAT_IDS`)을 돌려줍니다. 못 찾은 항목은 화면에서 빠지며, 응답의 `missing`에 id가 남습니다. 결과는 조합별로 1시간 캐시됩니다.

통계를 추가하려면 `STAT_SPECS`에 한 줄을 더하고(앱의 `STAT_CATALOG`에도 같은 id를 추가), **Deploy news proxy**를 다시 실행하세요. 앱만 새로 설치하고 서버를 다시 배포하지 않으면 새로 늘린 통계는 표시되지 않습니다.

## 금융사 주요 경영정보(FISIS)
앱의 **금융사 주요 경영정보** 화면은 금융통계정보시스템(FISIS) Open API를 서버(`/fisis/*`)로 중계합니다. 인증키는 서버 비밀값으로만 보관합니다.

1. [FISIS Open API](https://fisis.fss.or.kr/page/api-key.jsp)에서 인증키를 신청합니다(무료).
2. GitHub Secrets에 `FISIS_API_KEY`로 저장하고 Actions → **Deploy news proxy**를 다시 실행합니다.
- 권역: 증권사(F), 자산운용사(G), 은행(A), 생명보험(H), 손해보험(I), 저축은행(E), 카드사(C). 이름 끝에 `[폐]`가 붙은 폐업 회사는 제외합니다.
- 통계표 번호를 코드에 박지 않고, FISIS 통계목록·계정항목에서 통계표·계정 **이름**으로 총자산·총부채·BIS비율 등을 찾습니다(30일 보관). 어떤 통계표·계정을 쓰는지는 `GET /fisis/spec?sector=A`(앱 토큰 필요)로 확인할 수 있고, 앱 화면에도 항목마다 출처가 작게 표시됩니다.
- 총자산 순위(`/fisis/ranking`)는 회사 30곳씩 나눠 받고 7일 보관합니다. 회사 상세(`/fisis/profile`)는 12시간 보관합니다. FISIS 일일 호출 한도를 아끼기 위한 것입니다.

### OpenDART 연동(대표이사·최대주주·연결 재무)
FISIS에 없는 대표이사, 최대주주·지분율, 연결 재무제표는 [OpenDART](https://opendart.fss.or.kr)에서 가져옵니다.

1. OpenDART에서 인증키를 발급받습니다(무료).
2. GitHub Secrets에 `DART_API_KEY`로 저장하고 Actions → **Deploy news proxy**를 실행합니다.
- 배포할 때 `build-dart-corps.py`가 DART 고유번호 목록에서 금융회사만 골라 서버에 함께 올립니다. FISIS 회사 이름과 DART 회사 이름을 맞춰 보며, 앱 화면 아래에 어느 DART 회사와 맞췄는지 표시합니다.
- DART에 공시하지 않는 회사(최대주주 현황 등이 없는 회사)는 해당 항목이 "FISIS 미제공"으로 보입니다. 연결 재무는 DART 주요계정(연결) 기준입니다.
- Secrets에 `FISIS_API_KEY`와 `DART_API_KEY`가 모두 있어야 배포가 끝까지 진행됩니다.
