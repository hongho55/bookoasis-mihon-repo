# BookOasis Mihon 확장

[BookOasis](https://book.giwa.top)의 일반 라이브러리를 Mihon에서 검색하고 ZIP/CBZ 책으로 읽기 위한 확장입니다.

## Mihon에 확장 저장소 추가

1. Mihon에서 **더보기 → 설정 → 탐색 → 확장 저장소**로 이동합니다.
2. 다음 주소를 추가합니다.

   ```text
   https://raw.githubusercontent.com/hongho55/bookoasis-mihon-repo/main/index.min.json
   ```

3. **찾아보기 → 확장기능**으로 이동합니다.
4. 언어 필터에서 **Multi/다국어**를 활성화합니다.
5. **BookOasis**를 설치합니다.
6. 설치 후 BookOasis 소스 설정에서 BookOasis 아이디와 비밀번호를 입력합니다.

서버 주소 `https://book.giwa.top`은 확장에 이미 포함되어 있습니다. 이 주소를 Mihon 확장 저장소 주소로 추가하면 안 됩니다.

## 지원 범위

- 일반 BookOasis 라이브러리
- BookOasis Basic Auth
- 시리즈 검색 및 목록
- 시리즈 상세와 책/권 목록
- ZIP/CBZ 책의 Mihon 이미지 페이지 읽기

다음 기능은 1차 버전의 범위에서 제외했습니다.

- EPUB
- PDF
- 성인 라이브러리
- 직접 파일 다운로드

## 소스와 빌드

확장 어댑터 소스는 [`src/`](src/)에 있습니다. 최신 Keiyoushi Mihon 확장 도구체인의 `KeiSource` API를 사용합니다.

업스트림 빌드 프레임워크:

- [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source)

배포 APK는 [`repo.json`](repo.json)에 기록된 공개 서명 지문으로 서명되어 있습니다. 개인 서명키는 저장소에 공개하지 않습니다.

## 라이선스

확장 어댑터와 빌드에 사용한 관련 소스는 Apache License 2.0으로 배포합니다. 자세한 내용은 [`LICENSE`](LICENSE)를 확인하세요.
