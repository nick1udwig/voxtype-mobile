package io.voxtype.mobile;

import static org.junit.Assert.*;

import org.junit.Test;

public class EditingTest {
  @Test
  public void preservesTextAndReplacesSelection() {
    assertEquals("hello brave world", Editing.insert("hello world", 6, 6, "brave "));
    assertEquals("hello friend", Editing.insert("hello world", 11, 6, "friend"));
    assertEquals("😀 there", Editing.insert("😀", 2, 2, " there"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void unknownSelectionCannotClobberText() {
    Editing.insert("existing", -1, -1, "dictation");
  }

  @Test
  public void excludesEveryPasswordVariation() {
    for (int type : new int[] {0x81, 0x91, 0xe1, 0x12, 0x80081}) assertTrue(Editing.password(type));
    assertFalse(Editing.password(0x20001));
    assertFalse(Editing.password(0x21));
  }

  @Test(expected = IllegalArgumentException.class)
  public void hiddenTextCannotBeMistakenForAnEmptyField() {
    Editing.insert(null, 0, 0, "dictation");
  }
}
