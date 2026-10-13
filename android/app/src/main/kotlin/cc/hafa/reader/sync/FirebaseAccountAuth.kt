package cc.hafa.reader.sync

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import cc.hafa.reader.core.sync.AccountAuth
import cc.hafa.reader.core.sync.AuthState
import cc.hafa.reader.core.sync.SignInResult
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

// How recent a sign-in Firebase accepts for deleting it, less a margin.
private const val RECENT_SIGN_IN_MS = 4 * 60 * 1000L

/**
 * Google sign-in through the system's Credential Manager, handed to Firebase
 * Auth. Choosing an account needs the screen on show, from [activity].
 */
class FirebaseAccountAuth(
    private val auth: FirebaseAuth,
    private val context: Context,
    private val webClientId: String,
    private val activity: () -> Activity?,
) : AccountAuth {
    private val mutableState = MutableStateFlow(AuthState())

    override val state: StateFlow<AuthState> = mutableState.asStateFlow()

    init {
        auth.addAuthStateListener { current ->
            val user = current.currentUser
            mutableState.value = AuthState(known = true, uid = user?.uid, email = user?.email ?: "", photoUrl = user?.photoUrl?.toString() ?: "")
        }
    }

    /**
     * A Firebase credential for a Google account the person chooses; null when
     * they close the chooser. With [signedInEmail], to confirm the account
     * before deleting it, only accounts already used with the app are offered,
     * and a different one than that is refused.
     */
    private suspend fun googleCredential(signedInEmail: String? = null): AuthCredential? {
        val screen = activity() ?: throw IllegalStateException("no screen to choose an account on")
        val option = if (signedInEmail == null) {
            GetSignInWithGoogleOption.Builder(webClientId).build()
        } else {
            GetGoogleIdOption.Builder().setServerClientId(webClientId).setFilterByAuthorizedAccounts(true).build()
        }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        val result = try {
            CredentialManager.create(screen).getCredential(screen, request)
        } catch (_: GetCredentialCancellationException) {
            null
        } catch (missing: NoCredentialException) {
            throw IllegalStateException("no Google account to sign in with", missing)
        }
        val credential = result?.credential
        return if (credential == null) {
            null
        } else if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val google = GoogleIdTokenCredential.createFrom(credential.data)
            if (signedInEmail != null && !google.email.equals(signedInEmail, ignoreCase = true)) {
                throw IllegalStateException("another account was chosen than the one signed in")
            }
            GoogleAuthProvider.getCredential(google.idToken, null)
        } else {
            throw IllegalStateException("unexpected credential ${credential.type}")
        }
    }

    override suspend fun signIn(): SignInResult {
        val credential = googleCredential()
        return if (credential == null) {
            SignInResult.Closed
        } else {
            auth.signInWithCredential(credential).await()
            SignInResult.SignedIn
        }
    }

    override suspend fun signOut() {
        auth.signOut()
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }

    override suspend fun ensureRecentSignIn() {
        val user = auth.currentUser
        val lastSignIn = user?.metadata?.lastSignInTimestamp ?: 0
        if (user != null && System.currentTimeMillis() - lastSignIn > RECENT_SIGN_IN_MS) {
            val credential = googleCredential(user.email ?: "") ?: throw IllegalStateException("sign-in for deleting was closed")
            user.reauthenticate(credential).await()
        }
    }

    override suspend fun deleteUser() {
        val user = auth.currentUser
        if (user != null) {
            try {
                user.delete().await()
            } catch (_: FirebaseAuthRecentLoginRequiredException) {
                // Firebase wants a recent sign-in before deleting; ask for the account again
                val credential = googleCredential(user.email ?: "") ?: throw IllegalStateException("sign-in for deleting was closed")
                user.reauthenticate(credential).await()
                user.delete().await()
            }
        }
    }
}
