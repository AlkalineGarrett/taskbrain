package org.alkaline.taskbrain

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.alkaline.taskbrain.util.PermissionHelper
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var credentialManager: CredentialManager

    // Global finger state tracking - receives ALL touch events before Compose
    private val _isFingerDown = MutableStateFlow(false)
    val isFingerDown: StateFlow<Boolean> = _isFingerDown.asStateFlow()

    // Alarm ID to open when navigating from a notification tap
    private val _openAlarmId = MutableStateFlow<String?>(null)
    val openAlarmId: StateFlow<String?> = _openAlarmId.asStateFlow()

    // Permission request launcher for notification permission (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Log.w(TAG, "Notification permission denied")
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> _isFingerDown.value = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> _isFingerDown.value = false
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        auth = FirebaseAuth.getInstance()
        credentialManager = CredentialManager.create(this)

        // Request notification permission on Android 13+
        requestNotificationPermissionIfNeeded()

        // Check if launched from a notification tap
        _openAlarmId.value = intent.getStringExtra(EXTRA_OPEN_ALARM_ID)

        setContent {
            var user by remember { mutableStateOf(auth.currentUser) }

            DisposableEffect(auth) {
                val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
                    user = firebaseAuth.currentUser
                }
                auth.addAuthStateListener(listener)
                onDispose {
                    auth.removeAuthStateListener(listener)
                }
            }

            MainScreen(
                onSignInClick = { signIn() },
                isUserSignedIn = user != null,
                onSignOutClick = {
                    auth.signOut()
                },
                isFingerDown = isFingerDown,
                openAlarmId = openAlarmId,
                onOpenAlarmConsumed = { consumeOpenAlarmId() }
            )
        }
    }

    private fun signIn() {
        val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(
            getString(R.string.default_web_client_id)
        ).build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInWithGoogleOption)
            .build()

        credentialManager.getCredentialAsync(
            this,
            request,
            null,
            Executors.newSingleThreadExecutor(),
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    createGoogleIdToken(result.credential)
                }

                override fun onError(e: GetCredentialException) {
                    Log.e("MainActivity", "Credential Manager error", e)
                    runOnUiThread {
                        if (e is NoCredentialException) {
                            Toast.makeText(this@MainActivity, "No credentials found.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    private fun createGoogleIdToken(credential: Credential) {
        if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val credentialData = credential.data
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credentialData)
                firebaseAuthWithGoogle(googleIdTokenCredential.idToken)
            } catch (e: Exception) {
                 Log.e("MainActivity", "Error parsing Google ID Token", e)
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Sign-in successful; AuthStateListener will pick this up and update the UI
                } else {
                    Log.w(TAG, "signInWithCredential:failure", task.exception)
                    Toast.makeText(this, "Authentication Failed.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    /**
     * Requests notification permission on Android 13+ if not already granted.
     * This is required to show alarm notifications.
     */
    private fun requestNotificationPermissionIfNeeded() {
        // Skip in emulator/test mode — the dialog interrupts MainActivity
        // composition and races with NavHost graph setup.
        if (BuildConfig.USE_FIREBASE_EMULATOR) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!PermissionHelper.hasNotificationPermission(this)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_ALARM_ID)?.let {
            _openAlarmId.value = it
        }
    }

    /** Clears the pending alarm ID after it has been consumed. */
    fun consumeOpenAlarmId() {
        _openAlarmId.value = null
    }

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_OPEN_ALARM_ID = "extra_open_alarm_id"
    }
}