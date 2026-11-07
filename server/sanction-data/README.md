# 제재공시 세부내역 수집기

금감원 [금융회사 제재공시](https://www.fss.or.kr/fss/job/openInfo/list.do?menuNo=200476)를 2011년부터 모아
지적사항 단위로 정리한 `sanctions.json`을 만든다. 앱의 제재공시 › 세부내역·통계 탭이 이 파일을 쓴다.

- 목록(`pageUnit=100`) → 상세 → 첨부를 읽는다. 읽는 법은 `extract_text.py`: PDF는 pdftotext(-raw) → pypdfium2 → layout → pdfminer 순으로
  글자 층을 읽고, HWP는 `hwp_text.py`(배포용 문서는 ViewText를 AES로 풀어 읽음), HWPX는 XML. 글자 층이 없거나 쪽당 글자가 너무 적은 PDF
  (예: 'Print to PDF'로 만들어 글자가 그림 조각인 것)와 tif 등 이미지는 tesseract(한국어)로 OCR하고, 그렇게 읽은 건에는 `ocr: true`를 붙인다.
- 첨부가 여러 개이거나 붙임(과태료 산정내역)뿐인 공시는 첨부 글 + 상세 화면 '제재대상사실' 칸을 모두 파싱해 가장 잘 읽힌 것을 쓴다.
- OCR로도 못 읽은 공시는 `why: scan`(지적사항 미분류)이며, 앱·웹 통계 화면에 '통계 미반영 N건'으로 표시된다.
- 뽑는 값: 권역(회사명·담당부서로 추정), 회사명, 조치요구일, 담당부서, 기관·임원·직원 조치, 과태료·과징금(원),
  지적사항별 제목·구분(문책/주의/자율처리/개선/경영유의)·위반 법률과 조항.
- `.github/workflows/sanction-data.yml`이 매일 새벽에 실행해 `sanction-data` 브랜치에 올린다.
  이전 자료(`--prev`)와 첨부 글 보관(`--cache`)을 써서 새 공시만 받는다. 파서를 고치면 `PARSER_VERSION`을 올리거나
  수동 실행에서 "전부 다시 받기"를 고른다(보관한 글이 있으면 다시 내려받지 않는다).

로컬 실행: `pip install olefile pypdfium2 pdfminer.six pillow pycryptodome`, `apt install poppler-utils tesseract-ocr tesseract-ocr-kor` 후
`python3 build_sanctions.py out.json --cache cache --workers 4`
