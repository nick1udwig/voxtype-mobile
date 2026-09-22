package io.voxtype.mobile;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.ClipboardManager;
import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class EditorTargetTest {
  private static final String OWN = "io.voxtype.mobile", BROWSER = "org.chromium.chrome";

  private static final class Input implements EditorTarget.Input {
    String packageName = BROWSER;
    int type = 1, commits;
    String text;

    public String packageName() {
      return packageName;
    }

    public int inputType() {
      return type;
    }

    public void commit(String text) {
      commits++;
      this.text = text;
    }
  }

  private ClipboardManager clipboard() {
    return (ClipboardManager)
        RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE);
  }

  private AccessibilityNodeInfo node() {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setPackageName(BROWSER);
    node.setEditable(true);
    node.setEnabled(true);
    node.setFocused(true);
    node.setInputType(1);
    return node;
  }

  @Test
  public void browserEditorWithoutAccessibleTextCommitsExactlyOnce() {
    Input input = new Input();
    AccessibilityNodeInfo node = node();
    node.setText(null);
    node.setTextSelection(-1, -1);
    try (EditorTarget target = new EditorTarget(OWN, BROWSER, node, input)) {
      assertTrue(target.available());
      assertTrue(target.insert("hello 👋", clipboard()));
      assertEquals(1, input.commits);
      assertEquals("hello 👋", input.text);
      assertFalse(clipboard().hasPrimaryClip());
    }
  }

  @Test
  public void nativeComposerInputConnectionDoesNotRequireAccessibleEditorNode() {
    Input input = new Input();
    input.packageName = "example.native.composer";
    try (EditorTarget target = new EditorTarget(OWN, input.packageName, null, input)) {
      assertTrue(target.available());
      assertTrue(target.insert("hello", clipboard()));
      assertEquals(1, input.commits);
    }
  }

  @Test
  public void switchingAppsDoesNotWriteIntoStaleBrowserConnection() {
    Input input = new Input();
    try (EditorTarget target = new EditorTarget(OWN, "com.example.launcher", null, input)) {
      assertFalse(target.available());
      assertFalse(target.insert("hello", clipboard()));
      assertEquals(0, input.commits);
    }
  }

  @Test
  public void missingForegroundWindowAndOwnSettingsDoNotAcceptInput() {
    Input input = new Input();
    try (EditorTarget target = new EditorTarget(OWN, null, null, input)) {
      assertFalse(target.available());
    }
    input.packageName = OWN;
    try (EditorTarget target = new EditorTarget(OWN, OWN, null, input)) {
      assertFalse(target.available());
    }
  }

  @Test
  public void passwordMetadataBlocksEvenWhenNodeLooksLikePlainText() {
    for (int type : new int[] {0x81, 0x91, 0xe1, 0x12}) {
      Input input = new Input();
      input.type = type;
      try (EditorTarget target = new EditorTarget(OWN, BROWSER, node(), input)) {
        assertTrue(target.password());
        assertFalse(target.available());
        assertFalse(target.insert("hello", clipboard()));
        assertEquals(0, input.commits);
      }
    }
  }

  @Test
  public void passwordNodeBlocksEvenWhenInputMetadataLooksLikePlainText() {
    AccessibilityNodeInfo node = node();
    node.setPassword(true);
    try (EditorTarget target = new EditorTarget(OWN, BROWSER, node, new Input())) {
      assertTrue(target.password());
      assertFalse(target.available());
    }
  }

  @Test
  public void hiddenFocusedBrowserProxyIsAnEditor() {
    AccessibilityNodeInfo node = node();
    node.setVisibleToUser(false);
    try (EditorTarget target = new EditorTarget(OWN, BROWSER, node, null)) {
      assertTrue(target.available());
    }
  }

  @Test
  public void unfocusedNodesAndRawKeyOnlyConnectionsAreNotRichEditors() {
    Input input = new Input();
    input.type = 0;
    AccessibilityNodeInfo node = node();
    node.setFocused(false);
    try (EditorTarget target = new EditorTarget(OWN, BROWSER, node, input)) {
      assertFalse(target.available());
    }
  }

  @Test
  public void olderAndroidCanPasteIntoEditorWithUnknownSelection() {
    AccessibilityNodeInfo node = node();
    node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE);
    node.setText(null);
    node.setTextSelection(-1, -1);
    shadowOf(node).setRefreshReturnValue(true);
    shadowOf(node)
        .setOnPerformActionListener((action, args) -> action == AccessibilityNodeInfo.ACTION_PASTE);
    try (EditorTarget target = new EditorTarget(OWN, BROWSER, node, null)) {
      assertTrue(target.insert("hello", clipboard()));
      assertEquals("hello", clipboard().getPrimaryClip().getItemAt(0).getText());
      assertEquals(
          java.util.List.of(AccessibilityNodeInfo.ACTION_PASTE),
          shadowOf(node).getPerformedActions());
    }
  }

  @Test
  public void unavailableSelectionNeverReplacesUnknownExistingText() {
    AccessibilityNodeInfo node = node();
    node.setText(null);
    node.setTextSelection(-1, -1);
    shadowOf(node).setRefreshReturnValue(true);
    try (EditorTarget target = new EditorTarget(OWN, BROWSER, node, null)) {
      assertFalse(target.insert("hello", clipboard()));
      assertTrue(shadowOf(node).getPerformedActions().isEmpty());
    }
  }

  @Test
  public void changedPasswordNodeCannotReceiveFallbackPaste() {
    AccessibilityNodeInfo node = node();
    node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE);
    shadowOf(node).setRefreshReturnValue(true);
    try (EditorTarget target = new EditorTarget(OWN, BROWSER, node, null)) {
      node.setPassword(true);
      assertFalse(target.insert("hello", clipboard()));
      assertTrue(shadowOf(node).getPerformedActions().isEmpty());
    }
  }

  @Test
  public void findsFocusedEditorUnderContainerWithoutStandardFindFocusResult() {
    AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain();
    AccessibilityNodeInfo container = AccessibilityNodeInfo.obtain();
    AccessibilityNodeInfo editor = node();
    shadowOf(root).addChild(container);
    shadowOf(container).addChild(editor);
    org.robolectric.util.ReflectionHelpers.callInstanceMethod(
        root,
        "setSealed",
        org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, true));
    AccessibilityNodeInfo found = EditorTarget.findFocus(root);
    assertNotNull(found);
    assertTrue(EditorTarget.isEditor(found));
    found.recycle();
    editor.recycle();
    container.recycle();
    root.recycle();
  }
}
