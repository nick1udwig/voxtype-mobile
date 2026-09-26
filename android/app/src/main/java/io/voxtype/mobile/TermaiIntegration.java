package io.voxtype.mobile;

import android.view.accessibility.AccessibilityNodeInfo;

/** A visible, actionable marker in the focused application window, not a URL heuristic. */
final class TermaiIntegration {
  static final String MICROPHONE_LABEL = "Termai dictation microphone";

  static boolean hasMicrophone(AccessibilityNodeInfo root) {
    return root != null && find(root, new int[] {2048}, 0);
  }

  private static boolean find(AccessibilityNodeInfo node, int[] budget, int depth) {
    if (--budget[0] < 0 || depth > 64) return false;
    if (node.isVisibleToUser()
        && node.isClickable()
        && (MICROPHONE_LABEL.contentEquals(
                node.getContentDescription() == null ? "" : node.getContentDescription())
            || MICROPHONE_LABEL.contentEquals(node.getText() == null ? "" : node.getText()))) {
      return true;
    }
    for (int i = 0; i < node.getChildCount() && budget[0] > 0; i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child == null) continue;
      try {
        if (find(child, budget, depth + 1)) return true;
      } finally {
        child.recycle();
      }
    }
    return false;
  }
}
