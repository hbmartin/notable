@file:Suppress("AvoidVarsExceptWithDelegate")

package com.ethran.notable.sync

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

sealed interface DriveAuthorizationResult {
    data class Authorized(val accessToken: String) : DriveAuthorizationResult
    data class NeedsUserAction(val pendingIntent: PendingIntent) : DriveAuthorizationResult
    data class Unavailable(val reason: String) : DriveAuthorizationResult
}

/** Google Identity Services authorization using only the non-sensitive drive.file scope. */
@Singleton
class DriveAuthorizationManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    @Volatile private var accessToken: String? = null
    val currentToken: String? get() = accessToken

    fun isPlayServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    suspend fun authorizeSilently(accountName: String? = null): DriveAuthorizationResult {
        if (!isPlayServicesAvailable()) return DriveAuthorizationResult.Unavailable("Google Play services unavailable")
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .apply {
                if (!accountName.isNullOrBlank()) setAccount(Account(accountName, "com.google"))
            }
            .build()
        return suspendCancellableCoroutine { continuation ->
            Identity.getAuthorizationClient(context).authorize(request)
                .addOnSuccessListener { result ->
                    val token = result.accessToken
                    when {
                        !token.isNullOrBlank() -> {
                            accessToken = token
                            continuation.resume(DriveAuthorizationResult.Authorized(token))
                        }
                        result.hasResolution() -> continuation.resume(
                            DriveAuthorizationResult.NeedsUserAction(requireNotNull(result.pendingIntent))
                        )
                        else -> continuation.resume(DriveAuthorizationResult.Unavailable("Authorization returned no token"))
                    }
                }
                .addOnFailureListener { error ->
                    continuation.resume(DriveAuthorizationResult.Unavailable(error.message ?: "Authorization failed"))
                }
        }
    }

    fun acceptAccessToken(token: String) {
        accessToken = token
    }

    fun consumeAuthorizationResult(data: Intent): DriveAuthorizationResult = runCatching {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        val token = result.accessToken
        if (token.isNullOrBlank()) DriveAuthorizationResult.Unavailable("Authorization returned no token")
        else {
            accessToken = token
            DriveAuthorizationResult.Authorized(token)
        }
    }.getOrElse { DriveAuthorizationResult.Unavailable(it.message ?: "Authorization failed") }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
