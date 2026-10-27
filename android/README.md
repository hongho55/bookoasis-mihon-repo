# BookOasis Android 소스

- Mihon 기반 · 네이티브 BookOasis 홈 · 문서/만화 리더 · 다운로드 · 읽기 위치 동기화
- 빌드 환경: JDK 21 · Android SDK
- 빌드: `./gradlew :app:assembleRelease`
- 테스트: `./gradlew :app:testDebugUnitTest`
- 실제 HTTP 연동 테스트는 `BOOKOASIS_LIVE_TEST_CONFIG`로 별도의 로컬 합성 서버 설정을 지정할 때만 실행
- 패키지: `app.bookoasis.reader.directtest.dev`
- 배포 APK와 서명키가 다르면 기존 앱 위에 업데이트 불가 · 개인 서명키는 저장소에 포함하지 않음
- 원본: [Mihon](https://github.com/mihonapp/mihon) · Apache License 2.0 · [LICENSE](LICENSE)
- Copyright © 2015 Javier Tomás · © 2024 Mihon Open Source Project
- 변경 사항: BookOasis 연결·네이티브 홈·문서 읽기·다운로드·플러그인 없는 진행률 동기화·한국어 UI 및 앱 이름
