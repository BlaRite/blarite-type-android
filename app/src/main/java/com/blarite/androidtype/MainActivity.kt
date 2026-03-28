package com.blarite.androidtype

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

// This launcher activity is not the keyboard itself.
// Its job is to help the user grant permission, enable the IME,
// and switch into the voice bridge from Android's keyboard picker.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Compose replaces a traditional XML layout here.
        // We render one simple setup screen for first-time configuration.
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SetupScreen()
                }
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    // `LocalContext.current` gives Compose access to the Android Context that
    // traditional Views and Activities use for things like launching intents.
    val context = LocalContext.current
    // These `remember` values are the editable text currently shown in the fields.
    // When the user types, Compose updates the screen automatically.
    var apiKeyInput by remember {
        mutableStateOf(SpeechServiceConfigStore.getStoredApiKey(context))
    }
    var languageHintsInput by remember {
        mutableStateOf(SpeechServiceConfigStore.getStoredLanguageHintsText(context))
    }
    // This tracks whether we are using a saved key, bundled key, or no key.
    // The screen uses it to show a more helpful explanation to the user.
    var apiKeySource by remember {
        mutableStateOf(SpeechServiceConfigStore.getApiKeySource(context))
    }
    // We keep a small piece of UI state so the screen can immediately show
    // whether the microphone permission is currently granted.
    var hasMicrophonePermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    // This launcher asks Android for the runtime microphone permission.
    // When the user answers, Compose updates the screen automatically.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicrophonePermission = granted
    }
    // The page can be taller than the screen, so we make the whole layout scrollable.
    val scrollState = rememberScrollState()

    // The setup screen is intentionally simple: explain what the app does,
    // then give the user the few actions required to make an IME usable.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "BlaRite Android Type",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        // This short description tells the user what they can do on this screen
        // before they see the individual settings cards below.
        Text(
            text = "Configure speech service access, choose language hints, and make sure the keyboard is enabled before you start dictating.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // First card: values that control how the app talks to the speech service.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Speech service configuration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when (apiKeySource) {
                        SpeechServiceApiKeySource.SAVED -> "Using the Soniox API key saved in app settings."
                        SpeechServiceApiKeySource.BUNDLED -> "Using the bundled BuildConfig Soniox API key. Save a key below to override it."
                        SpeechServiceApiKeySource.MISSING -> "No Soniox API key is configured yet."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Soniox API key") },
                    singleLine = true
                )
                // Put the primary action and destructive/reset action side by side
                // so the user can see that both affect the same field.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            // Persist the typed key, then reload it from storage so the UI
                            // reflects the exact normalized value that was saved.
                            SpeechServiceConfigStore.saveApiKey(context, apiKeyInput)
                            apiKeyInput = SpeechServiceConfigStore.getStoredApiKey(context)
                            apiKeySource = SpeechServiceConfigStore.getApiKeySource(context)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text("Save API key")
                    }
                    OutlinedButton(
                        onClick = {
                            // Clearing the saved key may make the app fall back to the
                            // bundled BuildConfig key, depending on project setup.
                            SpeechServiceConfigStore.clearApiKey(context)
                            apiKeyInput = ""
                            apiKeySource = SpeechServiceConfigStore.getApiKeySource(context)
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text("Clear")
                    }
                }
                OutlinedTextField(
                    value = languageHintsInput,
                    onValueChange = { languageHintsInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Language hints") },
                    supportingText = { Text("Comma-separated ISO codes, for example: en, es") },
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            // Save the hints string exactly once here; the store turns it
                            // into a list later when the IME actually opens a Soniox session.
                            SpeechServiceConfigStore.saveLanguageHints(context, languageHintsInput)
                            languageHintsInput = SpeechServiceConfigStore.getStoredLanguageHintsText(context)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text("Save hints")
                    }
                    OutlinedButton(
                        onClick = {
                            SpeechServiceConfigStore.clearLanguageHints(context)
                            languageHintsInput = ""
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text("Clear")
                    }
                }
            }
        }
        // Second card: device-level setup steps required by Android before an IME can work.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Keyboard setup",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (hasMicrophonePermission) {
                        "Microphone permission granted"
                    } else {
                        "Microphone permission is required before the IME can listen"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Step 1: ask for mic permission so the IME can capture speech.
                FilledTonalButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("Grant microphone permission")
                }
                // Step 2: open the system screen where the user can enable this IME.
                // Android does not let apps silently enable keyboards for security reasons.
                Button(
                    onClick = {
                        // Android does not let apps enable keyboards silently.
                        // We can only open the system settings screen and let the user do it.
                        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("Open keyboard settings")
                }
                // Step 3: show the system keyboard picker so the user can switch into
                // the voice bridge without digging through settings again.
                OutlinedButton(
                    onClick = {
                        // This asks Android to show the system keyboard picker pop-up.
                        // It is the quickest way to switch into the voice keyboard.
                        val imm = context.getSystemService(InputMethodManager::class.java)
                        imm?.showInputMethodPicker()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text("Show input method picker")
                }
            }
        }
        // Final card: a little guidance about long-term key management.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
        ) {
            Text(
                text = if (apiKeySource == SpeechServiceApiKeySource.MISSING) {
                    "Add a Soniox API key here or provide one through BuildConfig before starting voice transcription. Long term, this should move to a backend-issued temporary key instead of bundling a long-lived secret into the app."
                } else {
                    "You can rotate the saved key at any time. For production, the preferred path is to fetch a temporary Soniox key from your backend rather than shipping a long-lived key inside the app."
                },
                modifier = Modifier.padding(18.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
