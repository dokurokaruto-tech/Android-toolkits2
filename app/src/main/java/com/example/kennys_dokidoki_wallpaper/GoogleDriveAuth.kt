package com.example.kennys_dokidoki_wallpaper

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale

/**
 * Google アカウントの認可だけを受け持つ層。
 * Drive の操作そのものは DriveApi、図書館データとの同期は DiaryDriveSync が持つ。
 */
object GoogleDriveAuth {

    /** アプリが作ったファイルだけを触る最小の権限 */
    private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val TOKEN_REQUEST = "oauth2:$DRIVE_SCOPE"
    private const val TAG = "GoogleDriveAuth"

    data class SignInResult(
        val email: String? = null,
        val errorMessage: String? = null,
        val statusCode: Int? = null
    )

    fun signInIntent(context: Context): Intent =
        GoogleSignIn.getClient(context, signInOptions()).signInIntent

    /** RESULT_OK かどうかにかかわらず結果データを解析し、失敗理由を捨てない。 */
    fun accountFromResult(data: Intent?): SignInResult {
        return try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            val email = account.email?.takeIf { it.isNotBlank() }
            when {
                email == null -> SignInResult(errorMessage = "Googleアカウントのメールアドレスを取得できませんでした。")
                !GoogleSignIn.hasPermissions(account, Scope(DRIVE_SCOPE)) -> {
                    Log.w(TAG, "Google Sign-In completed without the Drive file scope")
                    SignInResult(
                        errorMessage = "Google Driveへのアクセスが許可されませんでした。もう一度連携をお試しください。"
                    )
                }
                else -> SignInResult(email = email)
            }
        } catch (error: ApiException) {
            Log.e(TAG, "Google Sign-In failed: status=${error.statusCode}", error)
            SignInResult(
                errorMessage = signInErrorMessage(error.statusCode),
                statusCode = error.statusCode
            )
        } catch (error: Exception) {
            Log.e(TAG, "Google Sign-In result could not be read", error)
            SignInResult(errorMessage = "Googleアカウントの連携結果を読み取れませんでした。")
        }
    }

    /** Drive を叩くためのアクセストークン。期限切れは都度取り直す */
    suspend fun accessToken(context: Context, accountEmail: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val account = Account(accountEmail, GOOGLE_ACCOUNT_TYPE)
                GoogleAuthUtil.getToken(context, account, TOKEN_REQUEST)
            } catch (error: Exception) {
                Log.e(TAG, "Google Drive access token could not be obtained", error)
                null
            }
        }

    /** 実際にインストールされたAPKの署名SHA-1。OAuthクライアント設定の照合用。 */
    @Suppress("DEPRECATION")
    fun signingCertificateSha1(context: Context): String? = try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            packageInfo.signatures
        }
        signatures.orEmpty()
            .map { signature ->
                MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
                    .joinToString(":") { byte -> "%02X".format(Locale.US, byte.toInt() and 0xFF) }
            }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
    } catch (error: Exception) {
        Log.e(TAG, "Unable to read APK signing certificate", error)
        null
    }

    fun signOut(context: Context) {
        GoogleSignIn.getClient(context, signInOptions()).signOut()
    }

    private fun signInOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
            .build()

    private fun signInErrorMessage(statusCode: Int): String = when (statusCode) {
        GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Google Driveとの連携はキャンセルされました。"
        CommonStatusCodes.NETWORK_ERROR -> "ネットワークに接続できません。通信状態を確認して再度お試しください。"
        CommonStatusCodes.DEVELOPER_ERROR -> "Google OAuthの設定エラーです。設定値を確認してください。"
        else -> "Googleアカウントと連携できませんでした（エラーコード: $statusCode）。"
    }

    private const val GOOGLE_ACCOUNT_TYPE = "com.google"
}
