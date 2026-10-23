package me.goalong.sdk.compose

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassifier

/**
 * クリップボードに URL らしきものがあるかを**中身を読まずに**判定する(計画書 §2-4)。
 *
 * - `getPrimaryClipDescription()` のメタデータだけを見る(中身を読むのは入力欄に貼り付けたときだけ)
 * - API 31 以上で分類が完了している(`CLASSIFICATION_COMPLETE`)ときだけ `getConfidenceScore(TYPE_URL)` を使う
 * - それ以外(分類が未完了・失敗・API 30 以下)は「手がかりなし」
 * - Android 10 以降はアプリが前面でフォーカスを持っていないと読めないので、画面が前面に出てから呼ぶ
 *
 * 手がかりは「紹介がある証拠」ではなく、入口を開いて出すか畳んで出すかの判断材料にだけ使う。
 */
public object ClipboardHint {
    internal const val URL_CONFIDENCE_THRESHOLD = 0.5f

    public fun hasUrlHint(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
            if (!manager.hasPrimaryClip()) return false
            val description = manager.primaryClipDescription ?: return false
            decide(
                sdkInt = Build.VERSION.SDK_INT,
                classificationComplete = description.classificationStatus == ClipDescription.CLASSIFICATION_COMPLETE,
                urlScore = { description.getConfidenceScore(TextClassifier.TYPE_URL) },
            )
        } catch (_: RuntimeException) {
            // SecurityException(前面でない)・IllegalStateException(分類未完了)など
            false
        }
    }

    /** 判定の本体(JVM テスト用に Android の API から切り離す) */
    internal fun decide(sdkInt: Int, classificationComplete: Boolean, urlScore: () -> Float): Boolean {
        if (sdkInt < 31 || !classificationComplete) return false
        return try {
            urlScore() >= URL_CONFIDENCE_THRESHOLD
        } catch (_: IllegalStateException) {
            false
        }
    }
}
