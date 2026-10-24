package com.dietcode.diplo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dietcode.diplo.exec.DiploAccessibilityService
import com.dietcode.diplo.ui.AgentController
import com.dietcode.diplo.ui.AppLanguage
import com.dietcode.diplo.ui.OnboardingScreen
import com.dietcode.diplo.ui.DiploScreen

class MainActivity : ComponentActivity() {

    private lateinit var agent: AgentController
    private var accessibilityOn by mutableStateOf(false)
    private var micGranted by mutableStateOf(false)
    private var phoneGranted by mutableStateOf(false)
    private var onboardingComplete by mutableStateOf(false)
    private var preferredLanguage by mutableStateOf(AppLanguage.Hindi)
    private val prefs by lazy { getSharedPreferences("diplo_prefs", Context.MODE_PRIVATE) }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissions()
        }

    private val phonePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agent = AgentController(this, lifecycleScope)
        refreshPermissions()

        val savedLangCode = prefs.getString("preferred_language", "hi") ?: "hi"
        preferredLanguage = AppLanguage.entries.find { it.code == savedLangCode } ?: AppLanguage.Hindi

        // Draw under the status and navigation bars with dark icons on light warm ceramic background
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )

        setContent {
            val updateLanguage: (AppLanguage) -> Unit = { newLang ->
                preferredLanguage = newLang
                prefs.edit().putString("preferred_language", newLang.code).apply()
            }

            Crossfade(
                targetState = onboardingComplete,
                animationSpec = tween(500),
                label = "screen_flow",
            ) { completed ->
                if (!completed) {
                    OnboardingScreen(
                        language = preferredLanguage,
                        onLanguageChange = updateLanguage,
                        micGranted = micGranted,
                        phoneGranted = phoneGranted,
                        accessibilityOn = accessibilityOn,
                        onRequestMic = {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onRequestPhone = {
                            phonePermissionsLauncher.launch(
                                arrayOf(
                                    Manifest.permission.CALL_PHONE,
                                    Manifest.permission.READ_CONTACTS,
                                    Manifest.permission.WRITE_CONTACTS,
                                )
                            )
                        },
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onComplete = {
                            prefs.edit().putBoolean("onboarding_complete", true).apply()
                            onboardingComplete = true
                        },
                    )
                } else {
                    DiploScreen(
                        state = agent.state,
                        heard = agent.heard,
                        spokenText = agent.spokenText,
                        spokenWord = agent.spokenWord,
                        level = agent.level,
                        accessibilityOn = accessibilityOn,
                        language = preferredLanguage,
                        onLanguageChange = updateLanguage,
                        onMic = agent::startListening,
                        onStop = agent::stop,
                        onAnswer = agent::answer,
                        onSubmitText = agent::submitText,
                        onOpenUrl = { url -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                        onEnableAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    )
                }
            }
        }
    }

    private fun refreshPermissions() {
        micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val callOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        val contactsOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        phoneGranted = callOk && contactsOk

        accessibilityOn = DiploAccessibilityService.instance != null

        val previouslyCompleted = prefs.getBoolean("onboarding_complete", false)
        val allGranted = micGranted && phoneGranted && accessibilityOn
        onboardingComplete = previouslyCompleted && allGranted
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onDestroy() {
        agent.destroy()
        super.onDestroy()
    }
}
