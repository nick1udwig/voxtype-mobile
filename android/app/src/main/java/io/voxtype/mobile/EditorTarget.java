package io.voxtype.mobile;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

/** A fresh snapshot of the foreground app's input target. Never retains an old editor. */
final class EditorTarget implements AutoCloseable {
  interface Input {
    String packageName();

    int inputType();

    void commit(String text);
  }

  private final AccessibilityNodeInfo node;
  private final Input input;
  private final boolean password;

  EditorTarget(
      String ownPackage,
      CharSequence foregroundPackage,
      AccessibilityNodeInfo node,
      Input candidate) {
    this.node = node;
    boolean external = foregroundPackage != null && !ownPackage.contentEquals(foregroundPackage);
    boolean matchingInput =
        external
            && candidate != null
            && foregroundPackage.toString().equals(candidate.packageName());
    password =
        (node != null && (node.isPassword() || Editing.password(node.getInputType())))
            || (matchingInput && Editing.password(candidate.inputType()));
    input = matchingInput && candidate.inputType() != 0 ? candidate : null;
    nodeEligible =
        external
            && node != null
            && node.getPackageName() != null
            && foregroundPackage.toString().contentEquals(node.getPackageName())
            && isEditor(node);
  }

  private final boolean nodeEligible;

  boolean password() {
    return password;
  }

  boolean available() {
    return !password && (input != null || nodeEligible);
  }

  boolean insert(String text, ClipboardManager clipboard) {
    if (!available()) return false;
    if (input != null) {
      // commitText replaces the actual selection, including in WebView/contenteditable.
      // This API is one-way: after dispatch, do not also paste and risk duplicate text.
      input.commit(text);
      return true;
    }
    if (!node.refresh()
        || !isEditor(node)
        || node.isPassword()
        || Editing.password(node.getInputType())) return false;
    if (node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE)) {
      clipboard.setPrimaryClip(ClipData.newPlainText("Voxtype", text));
      if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true;
    }
    String old = node.getText() == null ? null : node.getText().toString();
    int start = node.getTextSelectionStart(), end = node.getTextSelectionEnd();
    if ("".equals(old)) start = end = 0;
    try {
      String updated = Editing.insert(old, start, end, text);
      Bundle args = new Bundle();
      args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated);
      if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false;
      int cursor = Math.min(start, end) + text.length();
      Bundle selection = new Bundle();
      selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor);
      selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor);
      node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
      return true;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  static boolean isEditor(AccessibilityNodeInfo node) {
    // Browser/terminal proxy inputs can be focused while marked invisible to accessibility.
    return node.isFocused()
        && node.isEnabled()
        && (node.isEditable()
            || node.getActionList()
                .contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
            || node.getActionList()
                .contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE));
  }

  static AccessibilityNodeInfo findFocus(AccessibilityNodeInfo root) {
    AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
    if (focused != null && (isEditor(focused) || focused.isPassword())) return focused;
    // Some browser accessibility trees report the focused container, hiding the editor below it.
    AccessibilityNodeInfo found = search(root, new int[] {256}, 0);
    if (found != null) {
      if (focused != null) focused.recycle();
      return found;
    }
    return focused;
  }

  private static AccessibilityNodeInfo search(AccessibilityNodeInfo node, int[] budget, int depth) {
    if (--budget[0] < 0 || depth > 32) return null;
    if (isEditor(node) || (node.isFocused() && node.isPassword()))
      return AccessibilityNodeInfo.obtain(node);
    for (int i = 0; i < node.getChildCount() && budget[0] > 0; i++) {
      AccessibilityNodeInfo child = node.getChild(i);
      if (child == null) continue;
      AccessibilityNodeInfo found = search(child, budget, depth + 1);
      child.recycle();
      if (found != null) return found;
    }
    return null;
  }

  @Override
  public void close() {
    if (node != null) node.recycle();
  }
}
