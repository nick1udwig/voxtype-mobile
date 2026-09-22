package io.voxtype.mobile;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.InputMethod;
import android.annotation.TargetApi;
import android.view.inputmethod.EditorInfo;

/** Observes the existing keyboard's editor; does not replace or display a keyboard. */
@TargetApi(33)
final class EditorInputMethod extends InputMethod {
  private final Runnable changed;

  EditorInputMethod(AccessibilityService service, Runnable changed) {
    super(service);
    this.changed = changed;
  }

  @Override
  public void onStartInput(EditorInfo info, boolean restarting) {
    changed.run();
  }

  @Override
  public void onFinishInput() {
    // The base implementation clears composition. Leave the user's keyboard composition alone.
    changed.run();
  }

  @Override
  public void onUpdateSelection(
      int oldStart, int oldEnd, int newStart, int newEnd, int candidatesStart, int candidatesEnd) {
    changed.run();
  }

  static EditorTarget.Input current(AccessibilityService service) {
    InputMethod method = service.getInputMethod();
    if (method == null || !method.getCurrentInputStarted()) return null;
    EditorInfo info = method.getCurrentInputEditorInfo();
    AccessibilityInputConnection connection = method.getCurrentInputConnection();
    if (info == null || connection == null) return null;
    return new EditorTarget.Input() {
      public String packageName() {
        return info.packageName;
      }

      public int inputType() {
        return info.inputType;
      }

      public void commit(String text) {
        connection.commitText(text, 1, null);
      }
    };
  }
}
