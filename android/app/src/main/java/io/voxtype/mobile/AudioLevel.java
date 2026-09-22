package io.voxtype.mobile;

/** Display-only microphone meter. Does not gate audio or perform speech recognition. */
final class AudioLevel {
  static float pcm16(byte[] bytes, int length) {
    int count = Math.min(bytes.length, length) / 2;
    if (count == 0) return 0;
    double power = 0;
    for (int i = 0; i < count * 2; i += 2) {
      int sample = (short) ((bytes[i] & 255) | (bytes[i + 1] << 8));
      double normalized = sample / 32768.0;
      power += normalized * normalized;
    }
    double db = 10 * Math.log10(Math.max(1e-12, power / count));
    return (float) Math.max(0, Math.min(1, (db + 60) / 48));
  }
}
