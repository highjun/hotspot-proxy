# 핫스팟 프록시 앱

폰의 Wi-Fi 핫스팟에 붙은 기기(예: 노트북)가 폰의 모바일 데이터를 쓰게 하는 proxy 앱이다. 폰에서 HTTP proxy(CONNECT 포함)와 SOCKS5 proxy 를 포트 하나(기본 8080)로 돌린다. 정한 것은 item [핫스팟 기기용 proxy 앱 만들기](<../../backlog/핫스팟 기기용 proxy 앱 만들기.md>)에 있다.

## 구조

- `app/src/main/java/dev/androidproxy/proxy/`: proxy 서버. Android 에 기대지 않는 Kotlin code 라 JVM 단위 테스트로 확인한다.
  - 첫 byte 가 `0x05` 면 SOCKS5, 아니면 HTTP 로 본다.
  - 사설 주소(RFC 1918), loopback, link-local, IPv6 ULA 에서 온 연결만 받는다.
  - TX 는 폰이 원격 서버로 보낸 byte, RX 는 원격 서버에서 받은 byte 다.
- `ProxyService.kt`: foreground service(`specialUse`). 도는 동안 partial wake lock 과 Wi-Fi lock 을 쥔다.
- `BootReceiver.kt`: 부팅 때 자동 시작 설정이 켜져 있고 proxy 가 켜져 있었으면 다시 켠다.
- `ui/`: 화면(`MainScreen.kt`)과 설정 화면(`SettingsScreen.kt`). 설정은 SharedPreferences 에 둔다.
- 앱 아이콘: adaptive icon(`res/mipmap-anydpi-v26/ic_launcher.xml`). 그림은 `res/drawable/ic_launcher_foreground.xml` 이고, 원본은 `store/icon.svg` 다.
- `store/`: Play 스토어 그림(앱 아이콘 `icon-512.png`, 그래픽 이미지 `feature-1024x500.png`)과 그 SVG 원본. `topics/app/` 에서 `uv run store/render.py` 로 다시 만든다.
  - 휴대전화 스크린샷 `screenshot-1.png`(켜짐), `screenshot-2.png`(꺼짐), `screenshot-3.png`(설정)는 1080×1920 RGB PNG 다. 기기 없이 JVM 에서 Paparazzi 로 그린다(아래 "스토어 스크린샷").

## 빌드와 테스트

이 폴더(`topics/app/`)에서 부른다. Android SDK 자리는 `local.properties` 의 `sdk.dir`(`~/Android/Sdk`)에 둔다. 이 파일은 git 이 받지 않는다.

```sh
./gradlew testDebugUnitTest   # proxy 단위 테스트
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

`testDebugUnitTest` 는 스토어 스크린샷 테스트(`app/src/test/java/dev/androidproxy/ui/StoreScreenshotTest.kt`)도 그린다. 그림을 비교하거나 쓰지는 않는다.

## 스토어 스크린샷

화면은 상태를 받아 그리기만 하는 `MainContent`·`SettingsContent` 로 그린다. 테스트가 가짜 상태를 넣고, 한국어·밝은 테마·Pixel 6 너비(420dpi)에 높이 1920 px 로 그린다.

```sh
./gradlew storeScreenshots   # store/screenshot-1.png … screenshot-3.png
```

- 기록한 원본은 `app/src/test/snapshots/images/` 에 생기고, git 이 받지 않는다. 이 task 가 RGB(알파 없음)로 바꿔 `store/` 에 쓴다.
- Paparazzi 는 `2.0.0-alpha02` 를 쓴다. `alpha04` 는 AGP 를 8.13.2 로 끌어올리고, Gradle 8.14 에서 테스트 보고서 단계가 실패한다.

## 설치

```sh
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 폰에서 확인

USB 로 붙인 폰의 proxy 를 PC 에서 `adb forward` 로 부른다. forward 로 온 연결은 폰에서 loopback 주소라 proxy 가 받는다.

1. 앱에서 ON 을 누른다. 알림에 "proxy 켜짐" 이 뜬다.
2. PC 에서 포트를 잇고 페이지를 받는다.

   ```sh
   ~/Android/Sdk/platform-tools/adb forward tcp:18080 tcp:8080
   curl -x http://127.0.0.1:18080 https://example.com -sS -o /dev/null -w '%{http_code}\n'   # HTTP CONNECT
   curl -x http://127.0.0.1:18080 http://example.com -sS -o /dev/null -w '%{http_code}\n'    # HTTP
   curl --socks5-hostname 127.0.0.1:18080 https://example.com -sS -o /dev/null -w '%{http_code}\n'  # SOCKS5
   ```

3. 화면의 TX/RX 가 늘었는지 본다.
4. service 가 도는지 본다: `~/Android/Sdk/platform-tools/adb shell "dumpsys activity services dev.sangjun.androidproxy"`
5. 끝나면 `~/Android/Sdk/platform-tools/adb forward --remove tcp:18080` 로 forward 를 지운다.

## 노트북에서 쓰기

1. 폰의 핫스팟을 켠다. 앱 화면에 proxy 주소(`IP:port`)가 보인다.
2. 노트북을 핫스팟에 붙이고, 노트북의 proxy 를 그 주소로 둔다(HTTP proxy 또는 SOCKS5).

화면이 꺼져도 proxy 가 멈추지 않게 하려면 설정 화면의 배터리 항목을 따른다. Samsung 기기는 "절전 예외 앱" 에 이 앱을 더한다.
