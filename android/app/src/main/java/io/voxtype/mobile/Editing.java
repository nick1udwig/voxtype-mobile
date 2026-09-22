package io.voxtype.mobile;

final class Editing {
  static String insert(String existing, int start, int end, String dictated) {
    if (existing == null
        || start < 0
        || end < 0
        || start > existing.length()
        || end > existing.length()) throw new IllegalArgumentException("Selection is unavailable");
    return existing.substring(0, Math.min(start, end))
        + dictated
        + existing.substring(Math.max(start, end));
  }

  static boolean password(int inputType) {
    int cls = inputType & 15, variation = inputType & 4080;
    return (cls == 1 && (variation == 128 || variation == 144 || variation == 224))
        || (cls == 2 && variation == 16);
  }
}
