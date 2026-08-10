# Hermes Pocket — Development Task for Qwen 3.8 Max

## Context

This is **Hermes Pocket**, a native Android chat client for **Hermes Agent** (Nous Research's open-source AI agent framework). The app connects to a self-hosted Hermes gateway over a WebSocket using JSON-RPC 2.0. Stack: **Kotlin, Jetpack Compose, Material 3, Hilt DI, OkHttp WebSocket**. Architecture: MVVM with Compose, StateFlow for state, SharedFlow for events, sealed classes for results.

## Repository layout

- `app/src/main/java/com/hermes/android/gateway/` — WebSocket client + JSON-RPC request/response types (protocol layer)
- `app/src/main/java/com/hermes/android/ui/screen/` — Compose screens
- `app/src/main/java/com/hermes/android/ui/viewmodel/` — ViewModels (state holders)
- `app/src/main/java/com/hermes/android/ui/component/` — Reusable composables
- `app/src/main/java/com/hermes/android/runtime/` — Hermes runtime abstraction (remote/embedded)
- `app/src/main/java/com/hermes/android/di/` — Hilt modules
- `app/src/main/java/com/hermes/android/service/` — Background services (cron, foreground)
- `app/src/main/java/com/hermes/android/work/` — WorkManager workers
- `app/build.gradle.kts` + `gradle/libs.versions.toml` — build config and dependency versions

## Build / verify commands

```bash
./gradlew assembleDebug   # build
./gradlew test            # run tests
./gradlew lint            # lint
```

## Wire protocol (JSON-RPC 2.0 over WebSocket)

Requests: `{"jsonrpc":"2.0","id":<long>,"method":"<name>","params":{...}}`
Key methods (see `gateway/GatewayRequest.kt`):

- Chat: `prompt.submit`, `session.create/list/resume/history/delete/interrupt/branch/steer/title/usage`
- Tools/config: `tools.list`, `tools.configure`, `model.options`, `model.save_key`, `config.get/set/show`
- Management: `skills.manage`, `skills.reload`, `plugins.manage`, `cron.manage`, `reload.mcp`, `reload.env`
- Control: `shell.exec`, `process.stop`, `commands.catalog`, `command.dispatch`
- Approval flows: `approval.respond`, `clarify.respond`, `sudo.respond`, `secret.respond`
- Memory/insights: `insights.get`, `credits.view`, `rollback.list/diff/restore`, `session.undo`
- Delegation: `delegation.status`, `delegation.pause`, `projects.tree`, `project.facts`

## Conventions (MUST follow)

- StateFlow for state, SharedFlow for events, Hilt for DI (no manual singletons)
- Timber for logging — **NEVER log tokens/credentials** (redact them)
- No force-unwraps (`!!`) — safe calls + null handling
- Sealed classes for results; keep gateway/ layer free of UI imports
- New screens go in `ui/screen/`, state in `ui/viewmodel/`, reusable UI in `ui/component/`

---

## TASK 1 (PRIMARY): Voice input & output

Hermes gateway supports STT (speech-to-text) and TTS (text-to-speech) via its config (`stt`/`tts` sections in server config.yaml). The app currently has NO voice support. Add:

1. **Voice message recording** — a mic button in the chat input bar (hold-to-record or tap-to-record). Use Android `MediaRecorder` to record to a temp file, then upload/attach it to the session the same way image/file attachments are handled today (find the existing attachment flow and extend it).
2. **Playback of assistant replies** — a speaker button on each assistant message bubble that converts the text to speech. Use Android `TextToSpeech` API. Respect the app's existing theme/persona settings.
3. If the gateway has a `voice.send` / `tts` RPC (check `commands.catalog` response shape), prefer gateway-side TTS; otherwise client-side `android.speech.tts.TextToSpeech` is acceptable.
4. Add a settings toggle in the config screen: "Voice replies" (on/off) and "Auto-play voice replies" — persisted in the app's existing settings store (find how other settings are persisted and reuse it).

Acceptance: record → send → assistant replies → tap speaker → hear the reply, all inside the app, no crash on permission denial (RECORD_AUDIO runtime permission handled gracefully with rationale).

## TASK 2 (SECONDARY): Biometric app lock

The app holds a gateway token that gives full control of the agent (shell exec, config, secrets). Add:

1. **Biometric lock** — on app foreground (`MainActivity.onStart`/`onResume`), if enabled, require `BiometricPrompt` (fingerprint/face) before showing chat content.
2. A "Security" section in the settings screen: toggle "Require biometric unlock", and optionally "Lock after N minutes in background" (default 5 min; use a foreground-detection timestamp, not a service).
3. Handle `BIOMETRIC_ERROR_NONE_ENROLLED` gracefully — prompt the user to enroll, don't crash, and keep the app usable (fall back to no lock with a warning banner).
4. Hide content from the Android task switcher (FLAG_SECURE on the window) when biometric lock is enabled.

Acceptance: enable lock → background the app → reopen → biometric prompt appears → cancel → app stays locked (blurred/blank), success → content shown.

## TASK 3 (OPTIONAL, only if time permits): Home-screen widget

A minimal AppWidget showing connection state (connected/disconnected/reconnecting) and the last message snippet, with a tap-to-open action. Use `AppWidgetProvider` + RemoteViews (Compose widgets are experimental — use classic RemoteViews). Update via the existing `ConnectionState` StateFlow (observe in `HermesGatewayService` or the singleton gateway client).

## Deliverables

1. Implement all accepted tasks cleanly, following the conventions above.
2. Update `AGENTS.md` only if file layout changes materially.
3. Run `./gradlew test` and `./gradlew lint` — fix everything you introduced; report any pre-existing failures separately (do NOT fix unrelated pre-existing issues unless trivial).
4. Write a short summary of: what you changed, files touched, any new dependencies (and why), and what you could NOT do (if anything).
