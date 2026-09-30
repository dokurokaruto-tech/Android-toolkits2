package com.example.kennys_dokidoki_wallpaper

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Google アカウントの認可だけを受け持つ層。
 * Drive の操作そのものは DriveApi、日記としての意味づけは DiaryDriveSync が持つ。
 */
object GoogleDriveAuth {

    /** アプリが作ったファイルだけを触る最小の権限 */
    private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val TOKEN_REQUEST = "oauth2:$DRIVE_SCOPE"

    fun signInIntent(context: Context): Intent {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
            .build()
        return GoogleSignIn.getClient(context, options).signInIntent
    }

    /** サインイン画面の戻り。連携したアカウントのメールアドレスを返す */
    fun accountFromResult(data: Intent?): String? = try {
        GoogleSignIn.getSignedInAccountFromIntent(data).getResult(Exception::class.java)?.email
    } catch (_: Exception) {
        null
    }

    /** Drive を叩くためのアクセストークン。期限切れは都度取り直す */
    suspend fun accessToken(context: Context, accountEmail: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val account = Account(accountEmail, GOOGLE_ACCOUNT_TYPE)
                GoogleAuthUtil.getToken(context, account, TOKEN_REQUEST)
            } catch (_: Exception) {
                null
            }
        }

    fun signOut(context: Context) {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        GoogleSignIn.getClient(context, options).signOut()
    }

    private const val GOOGLE_ACCOUNT_TYPE = "com.google"
}
