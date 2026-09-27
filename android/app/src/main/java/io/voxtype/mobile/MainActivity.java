package io.voxtype.mobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.*;

public class MainActivity extends Activity {
  private TextView status;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    ScrollView scroll = new ScrollView(this);
    LinearLayout layout = new LinearLayout(this);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setPadding(40, 64, 40, 40);
    scroll.addView(layout);
    TextView title = new TextView(this);
    title.setText("Voxtype Mobile");
    title.setTextSize(30);
    layout.addView(title);
    TextView intro = new TextView(this);
    intro.setText(
        "Dictate through your computer.\n\n"
            + "Connect this phone to your Tailscale network, then enter the private address and"
            + " pairing token from your computer.");
    intro.setPadding(0, 24, 0, 24);
    layout.addView(intro);
    EditText url = new EditText(this);
    url.setHint("wss://computer.tailnet.ts.net/voxtype/v1/dictate");
    url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    url.setSingleLine(true);
    url.setText(io.voxtype.mobile.Settings.url(this));
    layout.addView(url);
    EditText token = new EditText(this);
    token.setHint("Pairing token");
    token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    token.setSingleLine(true);
    layout.addView(token);
    try {
      token.setText(io.voxtype.mobile.Settings.token(this));
    } catch (Exception ignored) {
    }
    Button save = new Button(this);
    save.setText("Save connection");
    layout.addView(save);
    status = new TextView(this);
    layout.addView(status);
    save.setOnClickListener(
        v -> {
          try {
            io.voxtype.mobile.Settings.save(
                this, url.getText().toString().trim(), token.getText().toString().trim());
            status.setText("Connection saved");
          } catch (Exception e) {
            status.setText(e.getMessage());
          }
        });
    Button mic = new Button(this);
    mic.setText("Allow microphone");
    layout.addView(mic);
    mic.setOnClickListener(
        v ->
            requestPermissions(
                Build.VERSION.SDK_INT >= 33
                    ? new String[] {
                      Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS
                    }
                    : new String[] {Manifest.permission.RECORD_AUDIO},
                1));
    TextView disclosure = new TextView(this);
    disclosure.setText(
        "\n"
            + "Enable accessibility to show the floating button and insert text. Voxtype reads the"
            + " focused field locally to preserve existing text. It does not send screen text to"
            + " your computer. Recorded audio may be resent to recover an interrupted"
            + " dictation.\n");
    layout.addView(disclosure);
    Button accessibility = new Button(this);
    accessibility.setText("Enable dictation accessibility");
    layout.addView(accessibility);
    accessibility.setOnClickListener(
        v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    TextView usage = new TextView(this);
    usage.setText(
        "\n"
            + "Open a text field in another app. Hold the floating button and release to finish, or"
            + " tap once to start and again to finish. Password fields are excluded. If focus"
            + " disappears, the result is copied to the clipboard. Recording continues when the"
            + " screen locks. Unlock to finish; if a result completes while locked, it is delivered"
            + " after unlock.\n\n"
            + "If Android blocks accessibility for this APK, open App info → ⋮ → Allow restricted"
            + " settings.\n");
    layout.addView(usage);
    Button test = new Button(this);
    test.setText("Start / finish clipboard dictation");
    layout.addView(test);
    test.setOnClickListener(
        v -> {
          // Clipboard dictation must not retain focus in the pairing password field.
          layout.setFocusableInTouchMode(true);
          layout.requestFocus();
          getSystemService(android.view.inputmethod.InputMethodManager.class)
              .hideSoftInputFromWindow(layout.getWindowToken(), 0);
          if (DictationService.instance() != null) DictationService.instance().toggle();
          else status.setText("Enable the accessibility service first");
        });
    setContentView(scroll);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (status != null)
      status.setText(
          "Microphone: "
              + (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                      == PackageManager.PERMISSION_GRANTED
                  ? "allowed"
                  : "permission needed")
              + "\nAccessibility: "
              + (DictationService.instance() == null ? "enable in Settings" : "connected"));
  }
}
