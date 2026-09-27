package io.voxtype.mobile;

/** Keeps a recording alive through screen lock and delays text delivery until unlock. */
final class ScreenLockGate {
  private boolean screenOff;
  private String pendingTranscript;

  void screenOff() {
    screenOff = true;
  }

  boolean userPresent() {
    boolean resumed = screenOff;
    screenOff = false;
    return resumed;
  }

  boolean blocked(boolean keyguardLocked, boolean interactive) {
    return screenOff || keyguardLocked || !interactive;
  }

  String received(String transcript, boolean keyguardLocked, boolean interactive) {
    if (blocked(keyguardLocked, interactive)) {
      pendingTranscript = transcript;
      return null;
    }
    return transcript;
  }

  String takePending(boolean keyguardLocked, boolean interactive) {
    if (blocked(keyguardLocked, interactive)) return null;
    String transcript = pendingTranscript;
    pendingTranscript = null;
    return transcript;
  }

  void discard() {
    pendingTranscript = null;
  }
}
