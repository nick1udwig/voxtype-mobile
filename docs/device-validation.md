# Device acceptance checklist

Build and unit tests do not establish Android/OEM accessibility behavior. Run
these on the actual phone with Tailscale connected and the patched daemon as the
only running Voxtype service. Record Android version and app versions.

- Grant RECORD_AUDIO and enable the accessibility service. On Android 13+ a
  sideloaded app may require App info → menu → Allow restricted settings.
- In a native EditText, Chrome textarea, messaging composer and a Compose text
  field: button appears above the keyboard without stealing input focus.
- After updating to 0.1.1, disable/re-enable the accessibility service. Specifically
  test the ChatGPT Remote **native Android app** composer: button appears when
  focused, and a final transcript inserts exactly once while its keyboard stays open.
- Check the small lavender microphone button near the upper right against the
  reference screenshot, with the keyboard open and closed and after rotation.
- On recording, verify the order X / activity pill / purple check. The check
  must remain at the idle button's position through press/release. Voice activity
  follows microphone loudness and settles to dots in silence. X cancels without
  insertion or clipboard changes, including while waiting for a final transcript.
- Hold for 2 seconds, release: recording stops and one final result is inserted.
- Tap, speak, tap: first release keeps recording; second tap finishes. Include
  a fast release before the WebSocket handshake completes.
- On 0.1.3, drag the microphone across the display and along each edge. It should
  follow the finger fluidly without jumps or snapping on release. The provisional
  recording is discarded, with no insertion or clipboard changes. Include a
  long stationary hold followed by a drag, diagonal drags, and small finger jitter
  during ordinary hold-to-dictate.
- Tap to record, then drag the purple check: recording continues and releasing
  the drag does not submit. Tap it again to finish. Also drag while transcribing;
  the result should still insert exactly once. X should remain accessible when
  controls expand to either side or stack on a narrow display.
- Hide/show the keyboard, rotate, and disable/re-enable the service after a drag:
  position persists and stays within the safe display area above the keyboard.
  Add a second finger during a press and lock the screen mid-drag: no stuck
  pressed state or unintended submission. Recording should continue through lock
  if one was active.
- Test start via TalkBack ACTION_CLICK if used; its activation is toggle mode.
- Speak immediately on down: beginning is preserved by pre-admission buffering.
- Insert in the middle of existing text, replace a selection in either direction,
  and insert next to an emoji. Existing text and cursor should be preserved.
- Ordinary, numeric, web, and visible-password inputs: no button, no insertion.
  Changing to a password field during recording cancels it.
- Remove input focus during recording: button remains until finish, then result
  goes to clipboard. Use the in-app clipboard button with no external field.
- Editors with unavailable accessibility text/selection: Android 13+ uses the
  input connection to insert at the real cursor. Where that is unavailable, use
  accessibility paste; otherwise fall back to clipboard without replacing unknown text.
- Switch apps during transcription: insert only into the currently focused
  non-password editor, never the previous app's stale input connection. Go Home
  or remove focus: final text goes to clipboard. Continuous UI/content updates
  must not prevent the button from appearing.
- On 0.1.5, confirm the finish pill distinguishes Sending, Reconnecting and
  Transcribing. Compare 5-, 20- and 60-second dictations and note the final delay;
  a decoder backlog must not cut off the beginning or end of speech.
- On 0.1.4 or later, disconnect/reconnect Tailscale while speaking and again after tapping
  finish. Reconnection should preserve all retained speech and insert one complete
  result, including speech captured while offline. On 0.1.6, brief reconnects
  should resume without replaying accepted audio: logs show `resumed=true` and
  an increasing `accepted_bytes`, with one worker completion. Try a brief desktop
  busy period.
- On 0.1.6, interrupt Wi-Fi/cellular/Tailscale after finish and reconnect within
  90 seconds of completion. The same final should insert once without another
  recognition pass. Reconnect after daemon restart or after cache expiry: retained
  audio should replay successfully. Cancel while connecting and during upload;
  a new dictation must become available once prior inference joins. Cancel while
  offline: no late result may insert; the abandoned session eventually expires.
- On 0.1.8, verify the upgraded daemon acknowledges `audio_encoding:
  pcm_s16le_zlib_v1` and dictations retain their first/last words and punctuation.
  Compare release delay for the same 10-, 30- and 60-second speech on sufficient
  and poor uplinks, including speech with silence and noisy audio. Capture only
  timing/counter metadata; JVM tests and shaped localhost fixtures do not validate
  phone CPU cost, cellular latency or battery use.
- On 0.1.8, test the APK with an older daemon that omits `audio_encoding`; it
  must upload raw PCM and deliver one complete final. Reconnect during compressed
  upload and after finish: the checkpoint must count decoded PCM bytes and the
  retained suffix must resume without repeating or dropping speech. Include a
  daemon restart/downgrade between connections, which must negotiate again and
  safely replay/resume raw PCM. X must cancel without late insertion.
- Leave an unfinished recording disconnected for over 30 seconds, then retry.
  Verify full replay after expiry, without losing offline speech. After successful
  delivery, inspect metadata logs for one model load across repeated reconnects.
- Keep Tailscale disconnected until automatic retries run out: microphone stops,
  the pill says **Tap to retry**, and X remains available. Reconnect and tap the
  check; the original recording should insert once without speaking again.
- With a rejected token, the app should retain audio without repeatedly reconnecting.
  Correct the saved settings, then tap the check to retry. Entering an unlocked
  app password field, pressing X or disabling the service must discard retained
  audio and prevent scheduled retries or late results from inserting anything.
- Cancel, disable service, deny microphone, wrong token, disconnect
  Tailscale, stop daemon, slow connection: temporary failures recover as above;
  permanent microphone errors end capture and are surfaced.
- On 0.1.7, start a tap-mode dictation in an ordinary editor and let the screen
  time out. Keep speaking while locked for at least 30 seconds; unlock and tap
  the check. Verify speech before and after lock appears once in the focused
  editor. Repeat with the power button, and with the lock occurring during a
  held button press; no keyguard password should cancel the recording.
- Finish near a lock transition, or reach the five-minute limit while locked.
  The transcript should remain private until unlock, then insert into the
  current non-password editor or copy to clipboard if focus is gone. Verify X
  still cancels after unlock, and app shutdown still discards a pending result.
  Check the recording indicator/foreground notification while locked, battery
  use, and that the microphone is not silenced by the OS. A daemon reconnect
  during lock should still retain speech. GrapheneOS behavior must be checked
  on the actual Pixel; JVM tests cannot prove background microphone access.
- Microphone foreground notification and Android privacy indicator appear only
  during the session. The system binds accessibility with foreground capabilities (AOSP Android 15);
  OEM behavior still needs checking for background microphone promotion;
  test this specifically without the settings activity open. The app reports
  the platform error. Do not silently record through another service.
- During phone recording, desktop start/stop/cancel must not affect the phone.
  During desktop recording/transcribing, phone admission must return busy.
- Warm the patched daemon once, alternate phone and desktop 10 times, including
  cancel during preview: journal shows one model load; inspect process RSS and
  GPU memory if applicable. No separate `voxtype transcribe` process is launched.
- With Unified enabled, the ready event reports `preview: streaming`. Dictate
  for 5, 20 and 60 seconds: words appear in partial events while speaking and
  release flushes the tail without a full-recording batch pass. Record the final
  delay and confirm the first and last words survive. Include repeated phrases,
  punctuation, silence and cancellation while inference is active.
- On Omarchy, Super+Ctrl+X (the installed `voxtype record toggle` binding)
  starts dictation, shows the recording indicator, then stops and inserts the
  complete final. Test explicit `record start` / `record stop` as well as cancel.
  State should progress recording → transcribing → idle. File overrides must
  write only to the requested file. Configured processing/output hooks and
  modifier-release handling must behave like normal desktop dictation.
- Desktop Unified dictation inserts the complete final once. No cumulative
  preview is typed repeatedly; hold/release should retain the prior desktop UX.
- Confirm remote output never types, pastes, plays feedback, pauses media or runs
  desktop output hooks on Linux. Configured text post-processing still applies.

Android 13+ uses AccessibilityService's InputMethod API, without switching the
user's keyboard. Its commitText is one-way; a nonstandard editor can ignore it
without reporting failure. Test actual insertion, selection replacement and
emoji in the apps above; desktop regression tests cannot establish device behavior.

Platform source checked: [AccessibilityServiceConnection, Android 15](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android15-release/services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java).
