# BlaRite Android Type

This project is an Android 15+ Input Method Editor written in Kotlin. It behaves like a minimalist voice bridge instead of a full keyboard.

BlaRite Android Type is an independent project that uses the Soniox Speech-to-Text API. It is not affiliated with, endorsed by, or sponsored by Soniox.

## What it does

- Shows a larger voice-first preview panel in the keyboard area instead of a QWERTY layout.
- Uses a compact single-line header and smaller control row so more space is reserved for transcript text.
- Starts listening from `onStartInputView`.
- Streams raw 16 kHz mono PCM audio to the Soniox real-time WebSocket API.
- Shows live recognition status and transcript preview inside the IME surface in a 4-line scrollable viewport that follows the newest text.
- Buffers finalized Soniox tokens inside the IME and commits them into the active editor with `currentInputConnection.commitText()` only when the user taps `Done`.
- Uses Soniox manual finalization when the user taps `Done`, waiting for final tokens before closing the session.
- Auto-switches back to the previous keyboard with `switchToPreviousInputMethod()` after the user taps `Done`.
- Provides a dedicated `Switch` button so you can immediately leave the IME if the voice session gets stuck.
- Provides a `Config` button inside the IME so you can open the setup screen for the Soniox API key and language hints without leaving the keyboard flow manually.
- Closes the Soniox session gracefully from `onFinishInput`.
- Supports optional Soniox `language_hints` configured from the setup screen.
- Disables Soniox endpoint detection so recognition remains active until you explicitly tap `Done` or `Switch`.

## Project structure

- `app/src/main/java/com/blarite/androidtype/TransparentBridgeIME.kt`
  - The IME service.
  - Stable view-based keyboard-area preview UI.
  - Soniox-driven transcript preview and buffered final token commit on `Done`.
  - Auto-switch logic back to the previous keyboard.
- `app/src/main/java/com/blarite/androidtype/MainActivity.kt`
  - Scrollable, card-based setup screen for Soniox configuration, microphone permission, keyboard settings, and input method picker.
- `app/src/main/java/com/blarite/androidtype/VoiceBridgeInputView.kt`
  - The IME preview panel and waveform shown where the keyboard normally appears.
- `app/src/main/java/com/blarite/androidtype/RealtimeSpeechClient.kt`
  - OkHttp-based realtime speech client for Soniox WebSocket token updates and manual finalization.
- `app/src/main/java/com/blarite/androidtype/SpeechServiceConfigStore.kt`
  - SharedPreferences-backed storage for the Soniox API key override and language hints.
- `app/src/main/res/xml/method.xml`
  - IME declaration and subtype metadata.

## Soniox API key

The app can use a Soniox API key from either of these sources:

- A key saved at runtime in the setup screen inside the app.
- A bundled fallback from `BuildConfig.SONIOX_API_KEY`.

The saved runtime key takes precedence over the bundled fallback.

The setup screen also lets you save optional comma-separated Soniox language hints such as `en, es`, which are sent as `language_hints` to bias recognition toward expected languages.

The configuration page is organized into separate cards for Soniox settings, keyboard setup, and guidance so the controls remain usable on smaller screens.

The build still reads the bundled fallback from `local.properties`, a Gradle property, or an environment variable and exposes it as `BuildConfig.SONIOX_API_KEY`.

You can provide it with either of these approaches:

1. Add this to the gitignored root `local.properties` file:

```properties
SONIOX_API_KEY=your_key_here
```

2. Or add this to your user or project Gradle properties:

```properties
SONIOX_API_KEY=your_key_here
```

3. Or set an environment variable before building:

```powershell
$env:SONIOX_API_KEY="your_key_here"
```

The active IME flow now connects directly to the Soniox real-time WebSocket API using the saved runtime key when present, otherwise it falls back to `BuildConfig.SONIOX_API_KEY`.

For production security, Soniox recommends using a temporary API key issued by your own backend rather than embedding a long-lived key in a client application.

## Build

The project now includes a Gradle wrapper.

Use this command from the project root on Windows:

```powershell
.\gradlew.bat assembleDebug
```

The debug build path has been validated successfully with the current project sources.

## Run and enable

1. Open the project in Android Studio.
2. Sync Gradle.
3. Install the app on a device or emulator with microphone support.
4. Grant microphone permission from the launcher activity.
5. Open keyboard settings and enable `BlaRite Android Type`.
6. Use the input method picker to switch into the voice bridge.
7. Speak into the mic.
8. Tap `Done` to finalize and switch back to the previous keyboard, or use `Switch` to immediately leave the IME.

## Key implementation detail

The IME uses `switchToPreviousInputMethod()` to return to the last keyboard. If Android cannot restore the previous keyboard directly, the service falls back to the system input-method picker so you still have an escape hatch.
