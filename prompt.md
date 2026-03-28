# Project Prompt

Build and maintain an Android Input Method Editor that behaves as a transparent voice bridge instead of a traditional keyboard.

BlaRite Android Type is an independent project that uses the Soniox Speech-to-Text API. It is not affiliated with, endorsed by, or sponsored by Soniox.

## Current architecture

- `TransparentBridgeIME` extends `InputMethodService`.
- The active IME UI is rendered by `VoiceBridgeInputView` with a compact header/footer and a 4-line scrollable transcript viewport so the keyboard area uses space efficiently while showing recognition progress.
- `RealtimeSpeechClient` streams raw PCM audio to the Soniox real-time WebSocket API, delivers live/final token updates, and supports manual finalization.
- Final recognized text is buffered during the session and injected with `currentInputConnection.commitText()` only after the user taps `Done`.
- The `Done` action must manually finalize with Soniox before the IME closes and calls `switchToPreviousInputMethod()`.
- The IME also exposes a manual `Switch` action so the user can immediately leave the voice keyboard if the session gets stuck.
- The IME also exposes a `Config` action that opens `MainActivity` so the user can edit the Soniox API key and language hints from the keyboard surface.
- `MainActivity` exists only as a setup surface for Soniox settings, microphone permission, and keyboard enablement, using a scrollable grouped layout.
- The Soniox API is actively used by the IME service.
- The active Soniox API key comes from app-saved settings first, then falls back to `BuildConfig.SONIOX_API_KEY`.
- Optional Soniox language hints come from app-saved settings and are sent as `language_hints` in the websocket configuration.
- Soniox endpoint detection is disabled so the session stays active until the user explicitly finishes or switches away.

## Integration rules

- Preserve the minimalist IME UI. Do not add a full keyboard unless explicitly requested.
- Keep the auto-switch behavior intact.
- Keep `onStartInputView` as the place that starts the listening session.
- Keep `onFinishInput` as the place that guarantees the Soniox session closes.
- Prefer temporary Soniox API keys from a backend for production-hardening work.

## Configuration

- Target SDK: 35.
- User-facing IME name: `BlaRite Android Type`.
- Soniox API key source: app-saved settings first, then `BuildConfig.SONIOX_API_KEY`, populated from `SONIOX_API_KEY` in `local.properties`, a Gradle property, or an environment variable.
- The repository includes a Gradle wrapper, and `./gradlew assembleDebug` is the validated build path.
