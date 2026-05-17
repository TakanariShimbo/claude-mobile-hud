# 開発環境セットアップ

`claude-mobile-hud` v1.0 の Phase 4 (実装) 着手前に整える環境。Gradle root / Node プロジェクト / CI 雛形 / cxrglobal submodule は既にセットアップ済み。**Phone / Glass の Android サブプロジェクトのみ Android Studio で初期化が必要**。

---

## 1. 前提

- OS: Ubuntu 22.04+ (Linux)
- IDE: Android Studio (latest stable, バンドル JBR 推奨)
- Node.js 22+ (nvm 経由を推奨)
- JDK 17 (Android Studio の JBR を使えば OK)
- Git
- `~/Android/Sdk` に Android SDK (Android Studio の SDK Manager から)

JDK / Android SDK パスの環境変数 (Android Studio で開く場合は不要):
```bash
export JAVA_HOME=/opt/android-studio/jbr
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
```

---

## 2. リポジトリ初期 clone

```bash
git clone --recurse-submodules https://github.com/TakanariShimbo/claude-mobile-hud.git ~/claude-mobile-hud
cd ~/claude-mobile-hud
```

`--recurse-submodules` を忘れた場合:
```bash
git submodule update --init --recursive
```

これで `cxrglobal/` が展開される (CXR-L SDK ラッパー、Glass app の依存)。

---

## 3. Hub / Bridge (Node) のセットアップ

```bash
( cd hub    && npm install && npm run build && npm test )
( cd bridge && npm install && npm run build && npm test )
```

両方とも placeholder smoke test が pass すれば OK (Phase 4 で実テストを書く)。

---

## 4. `:protocol` (Kotlin) のセットアップ

```bash
./gradlew :protocol:build
```

(初回は Gradle 9.4.1 と依存ライブラリの DL に数分)。test 内容は空なので **BUILD SUCCESSFUL** が出れば OK。

---

## 5. Phone / Glass の Android スキャフォールド (要 Android Studio)

Phone / Glass は Android Studio の GUI で初期化する (Phase 3 §9.1 / AD-20)。

### 5.1 Phone app

1. Android Studio を起動 → **File → New → New Project**
2. テンプレ: **Empty Activity** (Compose)
3. パラメータ:
   - **Name**: `phone` (これがディレクトリ名 + 初期 app 名になる。app 表示名は手順 6 で書き換える)
   - **Package name**: `com.example.claudemobilehud.phone`
   - **Save location**: `~/claude-mobile-hud/phone` (Gradle root のサブディレクトリとして配置)
   - **Language**: Kotlin
   - **Minimum SDK**: API 31 (Android 12) — Phase 1 NFR-30 に整合
   - **Build configuration language**: Kotlin DSL (build.gradle.kts)
4. **Finish**
5. **app/ サブモジュールを flatten**: Wizard は `phone/app/` をネストして作るが、Gradle root は `:phone` として単一モジュール扱いする (`include(":phone")` で参照)。`phone/app/` 配下を `phone/` 直下に移動 + Wizard が作った top-level `phone/build.gradle.kts` (`apply false` だけのやつ) を削除:
   ```bash
   cd ~/claude-mobile-hud
   rm phone/build.gradle.kts                          # top-level (apply false) を削除
   mv phone/app/build.gradle.kts phone/build.gradle.kts
   mv phone/app/proguard-rules.pro phone/proguard-rules.pro
   mv phone/app/src phone/src
   rmdir phone/app
   ```
6. **AS-生成の standalone Gradle ファイルを削除** (root が肩代わりするため):
   ```bash
   rm -rf phone/settings.gradle.kts phone/gradlew phone/gradlew.bat \
          phone/gradle.properties phone/local.properties \
          phone/.gradle phone/.idea phone/gradle phone/.gitignore
   ```
7. **`phone/build.gradle.kts` をルート catalog 参照に書き換え**:
   - `compileSdk { release(36) { ... } }` → `compileSdk = 36`
   - `JavaVersion.VERSION_11` → `VERSION_21` (root toolchain と一致)
   - `kotlin { jvmToolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }` を追加
   - `dependencies` 先頭に `implementation(project(":protocol"))`
   - `plugins` は `android.application` + `kotlin.compose` のみ (`kotlin.android` は AGP 9 / Kotlin 2.x の compose plugin が抱えるので不要 — 重複 apply すると `Cannot add extension with name 'kotlin'` で失敗する)
8. **app 表示名を変更**: `phone/src/main/res/values/strings.xml` の `app_name` を `"Claude Mobile HUD Host"` に書き換える:
   ```xml
   <resources>
       <string name="app_name">Claude Mobile HUD Host</string>
   </resources>
   ```
9. ルート `settings.gradle.kts` を編集し、`include(":phone")` のコメントを外す
10. ルート直下に `local.properties` (`sdk.dir=/home/ai-workshop/Android/Sdk`) を作成 (root の `.gitignore` で除外済)

### 5.2 Glass app

Phone と同じ手順 (5〜9) を Glass にも適用:
- **Name**: `glass`
- **Package name**: `com.example.claudemobilehud.glass`
- **Save location**: `~/claude-mobile-hud/glass`
- **Minimum SDK**: API 31

flatten + standalone Gradle 削除 + build.gradle.kts 書き換え後、`glass/src/main/res/values/strings.xml` で:
```xml
<resources>
    <string name="app_name">Claude Mobile HUD Client</string>
</resources>
```

ルート `settings.gradle.kts` で `include(":glass")` のコメントを外す。

Glass app は CXR-L SDK (cxrglobal) を依存に持つ。Phase 4 で `glass/build.gradle.kts` に:
```kotlin
dependencies {
    implementation(project(":protocol"))
    implementation(libs.rokid.cxr.client.l)
    // cxrglobal submodule をライブラリとして取り込む場合は include(":cxrglobal") も検討
    // ...
}
```

### 5.3 ブランド名の補足

- アプリの表示名 (ランチャー / 通知): **`Claude Mobile HUD Host`** / **`Claude Mobile HUD Client`**
- パッケージ名 (内部識別): `com.example.claudemobilehud.phone` / `.glass`
- ディレクトリ名: `phone/` / `glass/` (Gradle root の中で)
- ドキュメント / リポジトリ名: `claude-mobile-hud`

「Host」= PC 側 Hub に対する**端末側のハブ** (Phone が中継拠点)。
「Client」= Phone に対する**ウェアラブル端末** (Glass はジェスチャ / HUD 表示専門)。

package 名は物理デバイス名 (`phone` / `glass`) を採用し、表示名で技術的な責務 (Host = state を持つ / Client = HUD 表示) を表現する二段構造にしている。

### 5.4 確認

```bash
./gradlew :phone:assembleDebug :glass:assembleDebug
```

両方の APK が `phone/build/outputs/apk/debug/phone-debug.apk` / `glass/build/outputs/apk/debug/glass-debug.apk` として生成されれば scaffold は完了。

---

## 6. CI

`.github/workflows/ci.yml` は以下 5 ジョブを実行:

- `:protocol` の Gradle build + test
- `hub` の npm build + test
- `bridge` の npm build + test
- `:phone` の `lintDebug` + `testDebugUnitTest`
- `:glass` の `lintDebug` + `testDebugUnitTest`

すべて JDK 21 (temurin) で実行 (root toolchain と一致)。

---

## 7. 既知の注意点

- **Gradle 9.4.1** を Wrapper で固定。`./gradlew --version` でバージョン確認可能
- **JAVA_HOME**: Android Studio バンドル JBR を export しないと `./gradlew` 直叩きで「JAVA_HOME is not set」エラー
- **Android SDK パス**: `local.properties` (gitignore 済み) で `sdk.dir=/path/to/sdk` を設定。Android Studio 経由なら自動生成
- **cxrglobal submodule**: clone 時に `--recurse-submodules` を忘れると Glass app のビルドが失敗する
- **Compose compiler**: Kotlin 2.x なので Plugin 経由 (`kotlin-compose` plugin) を必ず使う

---

## 8. Phase 4 着手チェックリスト

- [x] `git clone --recurse-submodules` で本リポジトリと cxrglobal を取得
- [x] `( cd hub && npm install )` + `( cd bridge && npm install )` 成功
- [x] `./gradlew :protocol:build` 成功
- [x] Phone app を Android Studio で scaffold + Gradle root に統合 (`phone/app/` を flatten)
- [x] Glass app を Android Studio で scaffold + Gradle root に統合 (`glass/app/` を flatten)
- [x] `./gradlew :phone:assembleDebug :glass:assembleDebug` 両方成功
- [x] CI yaml の Phone / Glass lint ジョブを有効化

→ **Phase 4 (実装) 着手可**。`:protocol` の wire 定義から実装開始。
