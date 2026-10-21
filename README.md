# GoAlong Android SDK(Kotlin)

> **プレビュー版です。** GoAlong のベータ版は iOS のみ対応で、Android(Google Play)は対応予定です。現時点では Google Play の課金は報酬の対象になりません。

アプリ事業者が自社の Android アプリに組み込む、インフルエンサーの紹介コード・紹介リンクによる deterministic attribution の SDK です。minSdk 24、Kotlin、Jetpack Compose(UI は任意)。

フィンガープリント・広告 ID・IP の収集は行いません。ユーザーが入力・貼り付けた紹介と、Google Play の Install Referrer(LP が付けた `goalong_code=<CODE>`)だけを根拠に計測します。

| モジュール | 内容 | 依存 |
|---|---|---|
| `goalong` | コア(headless)。API・保存・Install Referrer | kotlinx-coroutines、installreferrer のみ |
| `goalong-compose` | 既成 UI(`GoAlongReferralEntry` / `GoAlongCodeInput`)と `GoAlongCodeController` | Compose + Material3 |

## 1. 導入(JitPack)

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.blue-office.goalong-kotlin:goalong:0.1.0")
    // 既成 UI を使う場合(goalong も一緒に入ります)
    implementation("com.github.blue-office.goalong-kotlin:goalong-compose:0.1.0")
}
```

`INTERNET` 権限は SDK の Manifest が宣言しています。

## 2. 初期化(Application.onCreate で 1 回)

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GoAlong.configure(
            context = this,
            appId = "YOUR_APP_ID",   // アプリ事業者の GoAlong 管理画面、アプリ詳細「SDK の設定」の App ID
            appSlug = "your-app",    // 紹介リンクの slug。指定を推奨(他アプリのリンク混入を防ぐ)
            isActiveSubscriber = { isActiveSubscriber() },  // 3 章
        )
    }
}
```

`configure` は Application Context だけを保持し、裏で次を行います:

- installation の報告(失敗したら次回起動で再送)
- Install Referrer の読み取り(5 章)

## 3. RevenueCat 連携

### RevenueCat のユーザー ID を知らせる(必須)

`Purchases.configure` / `Purchases.logIn` の直後に呼びます:

```kotlin
Purchases.configure(PurchasesConfiguration.Builder(context, apiKey).appUserID(uid).build())
lifecycleScope.launch {
    GoAlong.setRevenueCatUserId(Purchases.sharedInstance.appUserID)  // ログイン時も同様に呼ぶ
    updateGoAlongAttributes()
}
```

これで以後 `applyCode` に毎回 `rcAppUserId` を渡す必要がなくなります。さらに:

- **未ログインの画面で紹介が適用され、特典がまだ付いていない場合に、ここで付与を取りに行きます**(戻り値が付与された特典)
- Install Referrer で読み取った紹介コードが保留中なら、ここで適用します

### 属性(Subscriber Attributes)

```kotlin
fun updateGoAlongAttributes() {
    val attributes = GoAlong.attributionAttributes().toMutableMap()
    // 省略だけでは RevenueCat に残っている旧属性は消えないので、空文字で削除する
    attributes.putIfAbsent("goalong_referred", "")
    attributes.putIfAbsent("goalong_offering", "")
    Purchases.sharedInstance.setAttributes(attributes)
}
```

`goalong_installation_id` は常に、紹介が成立していると `goalong_referred = "true"`、intro offer 特典があると `goalong_offering` が含まれます。
起動時・紹介の適用/取り消し後・`currentReferral()` の後・`GoAlong.events` で `InstallReferrerApplied` / `PerkGranted` を受け取った後に呼んでください。
Targeting で Offering を出し分ける場合は、ペイウォールを開く前に `Purchases.sharedInstance.syncAttributesAndOfferingsIfNeeded` を待ち、その戻り値の Offerings を使います。

### 購読中の判定(isActiveSubscriber)

有効な購読がある人は紹介の対象外です。SDK は Google Play の購入を直接見ないので、アプリが RevenueCat の `CustomerInfo` で判定して渡します。
**数えるのは有効な購読だけ**です。`entitlements.active` は GoAlong の特典(RevenueCat の promotional entitlement)や買い切りでも有効になるので使いません(iOS 版は買い切りを除外、Flutter 版の例も `rc_promo` を除外しています):

```kotlin
suspend fun isActiveSubscriber(): Boolean {
    val info = Purchases.sharedInstance.awaitCustomerInfo()
    // activeSubscriptions は自動更新の購読だけを含む(買い切りは含まない)。
    // GoAlong の特典などで付与された無料期間(rc_promo で始まる)は購読に数えない
    return info.activeSubscriptions.any { !it.startsWith("rc_promo") }
}
```

この関数の中で GoAlong の API(`currentReferral()` など)を呼んでもかまいません(SDK は内部のロックの外で呼びます)。

`configure(isActiveSubscriber = …)` か `GoAlong.setActiveSubscriberProvider { … }` で設定します。true のときは適用の通信をせず `ApplyResult.ExistingSubscriber` を返し、入口の UI も出しません。判定が失敗したときは false として続けます(サーバーでも `rcAppUserId` で再判定します)。

サーバー側の RevenueCat webhook は、アプリ事業者が GoAlong の管理画面の接続ウィザードで RevenueCat を接続すると自動で作成されます(コード不要)。Google Play の課金は、GoAlong が Android に対応するまで報酬・利用料の対象になりません(対応時期は決まり次第お知らせします)。

### SDK からの通知

```kotlin
// Application などで早めに collect する(起動直後に届くことがある)
appScope.launch {
    GoAlong.events.collect { event ->
        when (event) {
            is GoAlongEvent.InstallReferrerApplied -> updateGoAlongAttributes()
            is GoAlongEvent.PerkGranted -> updateGoAlongAttributes()
            is GoAlongEvent.InstallReferrerIneligible -> Unit
        }
    }
}
```

## 4. UI

### 入口(初回に着地する画面へ 1 行)

```kotlin
GoAlongReferralEntry(onApplied = { /* 適用完了 */ })
```

| 状況 | 表示 |
|---|---|
| この端末で適用済み(Install Referrer による自動適用を含む)・購読中 | 何も出さない |
| クリップボードに URL の手がかりがある | 入力欄を開いた状態 |
| 受付中のコードがある(サーバーの設定) | 小さな 1 行「紹介コードを入力する」 |
| それ以外 | 何も出さない |

クリップボードは**中身を読みません**。`getPrimaryClipDescription()` のメタデータだけを見て、Android 12(API 31)以上で分類が完了しているときだけ URL らしさのスコアを使います(API 30 以下や分類が未完了なら「手がかりなし」として、サーバーの受付設定に従います。受付中なら畳んだ 1 行、そうでなければ何も出しません)。中身が使われるのは、ユーザーが入力欄に貼り付けたときだけです。

### 入力欄(設定画面・ペイウォールなど)

```kotlin
GoAlongCodeInput(
    onApplied = { /* 適用完了 */ },
    onSkip = { /* 「あとで」。渡したときだけ表示 */ },
)
```

- 紹介リンク(`https://goalong.me/{slug}/{CODE}`)を貼り付けるとコードだけを取り出し、検証後に自動で適用します
- 手入力は 3 文字以上で 400ms 待って検証し、「このコードを使う」で確定します
- 別の紹介が適用済みなら「このコードに変更する」で置き換えます
- 誰の紹介かは表示しません。取り消しの UI は出しません(`GoAlong.removeReferral()` のみ)

### 文言と見た目

```kotlin
GoAlongCodeInput(
    labels = Labels(title = "招待コード", placeholder = "コードを入力"),
    theme = GoAlongTheme(accent = Color(0xFF0B57D0), onAccent = Color.White, cornerRadius = 8.dp),
)
```

既定はアプリの MaterialTheme に沿った控えめなモノクロです。`Labels` で指定しなかった文言は SDK の strings.xml(英語・日本語・韓国語・繁体字中国語)から、表示中の Context の言語で選ばれます。

**4 言語すべてに対応していないアプリ**では、アプリの他の文言と SDK の文言の言語が混ざらないように、アプリ側で言語を絞ってください:

```kotlin
android {
    androidResources {
        localeFilters += listOf("en", "ja")   // AGP 8.7 以前は defaultConfig { resourceConfigurations += listOf("en", "ja") }
    }
}
```

### 独自 UI(headless)

```kotlin
val scope = rememberCoroutineScope()
val controller = remember { GoAlongCodeController(scope) }
DisposableEffect(controller) { onDispose { controller.dispose() } }
val state by controller.state.collectAsState()
// state.phase: Idle / Checking / Valid(perk) / Applying / Failed(issue) / Done(perk)
TextField(value = state.code, onValueChange = controller::onCodeChange)
if (state.needsConfirmation) Button(onClick = { scope.launch { controller.apply() } }) { Text("使う") }
```

独自の入口を作る場合は、表示したときに `GoAlong.onReferralEntryShown()` を呼んでください(保留中の Install Referrer のコードを適用します)。

## 5. Install Referrer(Google Play 経由のインストール)

紹介リンクの LP は、Android 端末では Google Play へ `referrer=goalong_code%3D<CODE>` 付きで送ります。SDK は次のように扱います:

1. `configure` のあと、裏で一度だけ Install Referrer を読みます。Play ストアに接続できないなどの一時的な失敗は、次回起動で再試行します
2. 読み取れるのは `goalong_code=<CODE>` だけです(他のキーとの混在・重複・不正なエンコード・長すぎる入力は無視)
3. 読み取ったコードは保留(pending)にし、次のいずれかの時点で適用します(どれも購読中の判定を先に行います)
   - `setRevenueCatUserId` が呼ばれたとき
   - 入口の UI(`GoAlongReferralEntry`)が表示されたとき
   - 読み取りが終わった時点で、既に RevenueCat の ID があるか入口が表示済みのとき
4. 通信に失敗したら保留のまま、次の機会に再試行します
5. 手入力などで別の紹介が先に適用されていたら上書きしません。期限切れ・見つからない・購読中も適用しません
6. 利用者が紹介を取り消した後(`removeReferral()`、またはサーバーが取り消し済みと返したとき)は、Install Referrer の読み取り前であっても、以後は自動適用しません
7. `setRevenueCatUserId` の中で自動適用したときに特典が付与されたら、その特典を返します(追加の付与依頼はしません)

状態は `GoAlong.installReferrerState`(UNREAD / NONE / PENDING / APPLIED / INELIGIBLE)で確認できます。適用の method は `INSTALL_REFERRER` として記録されます(統計用)。

## 6. その他の API

| API | 内容 |
|---|---|
| `validateCode(code)` | 適用せず検証(特典のプレビュー) |
| `applyCode(code, method, rcAppUserId, confirmReplace)` | 適用。結果は `Applied` / `AlreadyApplied` / `Conflict` / `ExistingSubscriber` / `NotFound(error)` |
| `handleDeepLink(uri)` / `extractCode(text)` | 紹介リンクからコードを取り出す(適用はしない) |
| `currentReferral()` | 現在の状態(`None` / `Applied(perk)` / `Removed`) |
| `removeReferral()` | 取り消し |
| `referralEntryEnabled()` | 入口を出してよいか(サーバー指定の秒数キャッシュ) |
| `installationId` / `hasAppliedReferralLocally` / `cachedAppliedPerk` | 端末の状態 |

エラーは `GoAlongException.NotConfigured` / `Network` / `Server(statusCode)` / `Storage` です。`Storage` は端末への保存に失敗したとき(空き容量不足など)で、保存できなかった状態はサーバーにも送りません(installation ID が保存できなければ通信しません)。サーバー側では適用が成立していることがあるので、後で `currentReferral()` で確かめてください。

## 7. 保存とバックアップ

SDK の状態(installation ID、適用済みの状態、RevenueCat の ID、Install Referrer の状態)は `Context.noBackupFilesDir` の `goalong.json` に保存します(一時ファイルに書いてディスクに同期してから置き換え、保存に成功したときだけ反映)。SharedPreferences は使いません。Auto Backup や端末移行で installation ID や適用済みの状態が別の端末に引き継がれないようにするためで、アプリ側でバックアップの除外設定を追加する必要はありません。再インストールすると新しい installation として扱われます。

## 8. 開発

```sh
cd sdk/android
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # JDK 17 固定
./gradlew :goalong:test :goalong-compose:test :sample:assembleDebug
```

- `sample/` は動作確認用のアプリです(公開しません)。debug は偽の appId を使うので通信は失敗しますが、UI は表示されます
- 公開は JitPack(`jitpack.yml`)。`-PgoalongVersion=<version>` で版を指定します(既定 0.1.0)
