package me.goalong.sdk.compose

import me.goalong.sdk.ApplyMethod
import me.goalong.sdk.ApplyResult
import me.goalong.sdk.CodeValidation
import me.goalong.sdk.GoAlong
import me.goalong.sdk.GoAlongReferralSnapshot

/** コントローラが使う SDK の機能。テストでは偽物に差し替える */
internal interface GoAlongCodeApi {
    suspend fun validateCode(code: String): CodeValidation
    suspend fun applyCode(code: String, method: ApplyMethod, rcAppUserId: String?, confirmReplace: Boolean): ApplyResult
    suspend fun currentReferral(): GoAlongReferralSnapshot
    fun extractCode(text: String): String?
    fun hasRevenueCatUserId(): Boolean

    /** 端末に残っている適用済みの状態(通信を待たずに出すため)。未適用なら null */
    fun localAppliedSnapshot(): GoAlongReferralSnapshot?
}

internal object DefaultGoAlongCodeApi : GoAlongCodeApi {
    override suspend fun validateCode(code: String) = GoAlong.validateCode(code)

    override suspend fun applyCode(code: String, method: ApplyMethod, rcAppUserId: String?, confirmReplace: Boolean) =
        GoAlong.applyCode(code, method, rcAppUserId, confirmReplace)

    override suspend fun currentReferral() = GoAlong.currentReferral()

    override fun extractCode(text: String) = GoAlong.extractCode(text)

    override fun hasRevenueCatUserId() = !GoAlong.revenueCatUserId.isNullOrEmpty()

    override fun localAppliedSnapshot(): GoAlongReferralSnapshot? =
        if (GoAlong.hasAppliedReferralLocally) {
            GoAlongReferralSnapshot.Applied(creator = null, appliedAtMs = null, perk = GoAlong.cachedAppliedPerk)
        } else {
            null
        }
}
