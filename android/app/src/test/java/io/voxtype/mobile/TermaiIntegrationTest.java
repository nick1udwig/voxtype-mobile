package io.voxtype.mobile;

import static org.junit.Assert.*;

import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class TermaiIntegrationTest {
  @Test
  public void onlyVisibleActionableMicrophoneSuppressesOverlay() {
    AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
    node.setContentDescription(TermaiIntegration.MICROPHONE_LABEL);
    node.setClickable(true);
    node.setVisibleToUser(true);
    assertTrue(TermaiIntegration.hasMicrophone(node));
    node.setVisibleToUser(false);
    assertFalse(TermaiIntegration.hasMicrophone(node));
    node.setVisibleToUser(true);
    node.setClickable(false);
    assertFalse(TermaiIntegration.hasMicrophone(node));
    node.setClickable(true);
    node.setContentDescription("Other app microphone");
    assertFalse(TermaiIntegration.hasMicrophone(node));
    node.setContentDescription(null);
    node.setText(TermaiIntegration.MICROPHONE_LABEL);
    assertTrue(TermaiIntegration.hasMicrophone(node));
    assertFalse(TermaiIntegration.hasMicrophone(null));
    node.recycle();
  }
}
