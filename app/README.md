# Amuless app

Flutter アプリ本体です。Android のネイティブ層(`android/`)は omi の BLE 実装と Limitless フラッシュの読み出し(Transcribe Later)をそのまま使っています。

- `lib/` — 画面とペアリング(`lib/ble/pendant_ble.dart` が omi の Pigeon ブリッジを呼びます)
- `android/` — BLE フォアグラウンドサービスとバッチ書き込み(Kotlin、`com.friend.ios.*`)

ビルド: `flutter build apk --release`(Flutter 3.44.5 / JDK 21)
