package io.voxtype.mobile;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;

public class DictationService extends AccessibilityService {
  private static java.lang.ref.WeakReference<DictationService> current =
      new java.lang.ref.WeakReference<>(null);

  static DictationService instance() {
    return current.get();
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private final ScreenLockGate screenLock = new ScreenLockGate();
  private WindowManager wm;
  private PowerManager power;
  private FrameLayout overlay;
  private LinearLayout controls;
  private DictationButton button;
  private CancelButton cancelButton;
  private VoiceActivityView activity;
  private WindowManager.LayoutParams params;
  private DictationClient client;
  private boolean waiting, added;
  private boolean touching, destroyed, layoutPending;
  private OverlayPosition position;
  private final Rect safeBounds = new Rect();
  private int visibleBottom;
  private float dragOriginX, dragOriginY;
  private final Runnable layoutFrame =
      () -> {
        layoutPending = false;
        layoutOverlay();
      };
  private int generation;
  private final Runnable refresh = this::refreshButton;
  private final BroadcastReceiver screenEvents =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
            screenLock.screenOff();
            if (button != null) button.abandonTouch();
          } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())
              || (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) && !locked())) {
            screenLock.userPresent();
            main.postDelayed(DictationService.this::deliverAfterUnlock, 300);
          }
          refreshButton();
        }
      };

  @Override
  protected void onServiceConnected() {
    current = new java.lang.ref.WeakReference<>(this);
    wm = getSystemService(WindowManager.class);
    power = getSystemService(PowerManager.class);
    if (!power.isInteractive()) screenLock.screenOff();
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel("dictation", "Dictation", NotificationManager.IMPORTANCE_LOW));
    position =
        new OverlayPosition(
            Settings.prefs(this).getFloat("button_x", 1),
            Settings.prefs(this).getFloat("button_y", .26f));
    overlay = new FrameLayout(this);
    overlay.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    overlay.setClipChildren(false);
    controls = new LinearLayout(this);
    controls.setOrientation(LinearLayout.HORIZONTAL);
    controls.setGravity(Gravity.CENTER_VERTICAL);
    controls.setVisibility(View.GONE);
    overlay.addView(controls, new FrameLayout.LayoutParams(dp(146), dp(48)));
    button =
        new DictationButton(
            this,
            new DictationButton.Controller() {
              public boolean active() {
                return client != null;
              }

              public boolean waiting() {
                return waiting;
              }

              public void toggle() {
                DictationService.this.toggle();
              }

              public void beginTouch() {
                beginButtonTouch();
              }

              public void drag(float deltaX, float deltaY) {
                dragButton(deltaX, deltaY);
              }

              public void endTouch(boolean moved) {
                endButtonTouch(moved);
              }

              public void act(Gesture.Action action) {
                switch (action) {
                  case START:
                    start();
                    break;
                  case FINISH:
                    finish();
                    break;
                  case CANCEL:
                    cancel(null);
                    break;
                  default:
                    break;
                }
              }
            });
    button.setContentDescription("Dictate: hold and release, or tap to start and stop");
    cancelButton = new CancelButton(this);
    LinearLayout.LayoutParams cancelLayout = new LinearLayout.LayoutParams(dp(48), dp(48));
    cancelLayout.setMarginEnd(dp(6));
    controls.addView(cancelButton, cancelLayout);
    cancelButton.setOnClickListener(v -> cancel(null));
    activity = new VoiceActivityView(this);
    LinearLayout.LayoutParams activityLayout = new LinearLayout.LayoutParams(dp(92), dp(48));
    controls.addView(activity, activityLayout);
    overlay.addView(button, new FrameLayout.LayoutParams(dp(48), dp(48)));
    params =
        new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.LEFT;
    params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
    // Own the screen-space origin and clamp to system/keyboard insets ourselves.
    if (Build.VERSION.SDK_INT >= 30) params.setFitInsetsTypes(0);
    IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
    screenFilter.addAction(Intent.ACTION_SCREEN_ON);
    screenFilter.addAction(Intent.ACTION_USER_PRESENT);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(screenEvents, screenFilter, Context.RECEIVER_NOT_EXPORTED);
    else registerReceiver(screenEvents, screenFilter);
    refreshButton();
  }

  void toggle() {
    if (client == null) start();
    else if (!waiting) finish();
  }

  private void start() {
    if (client != null) return;
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      toast("Open Voxtype Mobile and allow microphone access");
      return;
    }
    if (screenBlocked()) return;
    String url = Settings.url(this), token;
    try {
      token = Settings.token(this);
    } catch (Exception e) {
      toast("Save the pairing token again");
      return;
    }
    if (url.isEmpty() || token.isEmpty()) {
      toast("Configure the connection in Voxtype Mobile first");
      return;
    }
    try {
      PendingIntent settings =
          PendingIntent.getActivity(
              this,
              0,
              new Intent(this, MainActivity.class),
              PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
      Notification notification =
          new Notification.Builder(this, "dictation")
              .setSmallIcon(android.R.drawable.ic_btn_speak_now)
              .setContentTitle("Voxtype dictation")
              .setContentText("Recording to your computer")
              .setContentIntent(settings)
              .setOngoing(true)
              .build();
      startForeground(
          1,
          notification,
          Build.VERSION.SDK_INT >= 30 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
      final int id = ++generation;
      client =
          new DictationClient(
              new DictationClient.Listener() {
                private void post(Runnable work) {
                  main.post(
                      () -> {
                        if (id == generation && client != null) work.run();
                      });
                }

                public void state(String text) {
                  post(
                      () -> {
                        if (text.startsWith("Transcribing")) waiting = true;
                        activity.status(text);
                        activity.setContentDescription(text);
                        refreshButton();
                      });
                }

                public void partial(String text) {
                  post(() -> activity.setContentDescription("Recording. " + text));
                }

                public void level(float level) {
                  post(() -> activity.level(level));
                }

                public void result(String text) {
                  post(
                      () -> {
                        if (!text.isEmpty()) {
                          String ready = screenLock.received(text, locked(), power.isInteractive());
                          if (ready == null) notifyPendingTranscript();
                          else deliver(ready);
                        } else if (!screenBlocked()) toast("No speech detected");
                        cleanup();
                      });
                }

                public void error(String text) {
                  post(() -> cancel(text));
                }

                public void recoverable(String text) {
                  post(
                      () -> {
                        waiting = false;
                        refreshButton();
                        toast(text);
                      });
                }
              });
      client.start(url, token);
      refreshButton();
    } catch (Exception e) {
      cancel("Cannot start microphone: " + e.getMessage());
    }
  }

  private void finish() {
    if (client == null || waiting) return;
    waiting = true;
    if (client.canRetry()) {
      try {
        client.retry(Settings.url(this), Settings.token(this));
      } catch (Exception e) {
        waiting = false;
        toast(
            "Save the connection settings, then tap the check to retry. Audio is still retained.");
      }
    } else client.finish();
    refreshButton();
  }

  private void cancel(String message) {
    if (client != null) client.cancel();
    screenLock.discard();
    getSystemService(NotificationManager.class).cancel(2);
    cleanup();
    if (message != null) toast(message);
  }

  private void cleanup() {
    ++generation;
    client = null;
    waiting = false;
    stopForeground(STOP_FOREGROUND_REMOVE);
    if (button != null) {
      activity.reset();
      refreshButton();
    }
  }

  private boolean locked() {
    return getSystemService(KeyguardManager.class).isKeyguardLocked();
  }

  private boolean screenBlocked() {
    return screenLock.blocked(locked(), power.isInteractive());
  }

  private void notifyPendingTranscript() {
    try {
      getSystemService(NotificationManager.class)
          .notify(
              2,
              new Notification.Builder(this, "dictation")
                  .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                  .setContentTitle("Voxtype dictation ready")
                  .setContentText("Unlock to insert your transcript")
                  .setAutoCancel(true)
                  .build());
    } catch (SecurityException ignored) {
      // Notification permission is optional; the result stays in memory.
    }
  }

  private void deliverAfterUnlock() {
    if (destroyed) return;
    String text = screenLock.takePending(locked(), power.isInteractive());
    if (text == null) return;
    getSystemService(NotificationManager.class).cancel(2);
    deliver(text);
  }

  @Override
  @android.annotation.TargetApi(33)
  public android.accessibilityservice.InputMethod onCreateInputMethod() {
    return new EditorInputMethod(this, this::scheduleRefresh);
  }

  private boolean termaiMicrophone;

  private EditorTarget target() {
    // The accessibility overlay or keyboard may be the active window. Resolve the
    // window with application input focus explicitly, never accessibility focus.
    AccessibilityNodeInfo root = null;
    for (AccessibilityWindowInfo window : getWindows()) {
      if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused()) {
        if (root != null) root.recycle();
        root = window.getRoot();
      }
      window.recycle();
    }
    if (root == null) root = getRootInActiveWindow();
    termaiMicrophone = TermaiIntegration.hasMicrophone(root);
    AccessibilityNodeInfo node = root == null ? null : EditorTarget.findFocus(root);
    CharSequence foregroundPackage = root == null ? null : root.getPackageName();
    if (root != null) root.recycle();
    return new EditorTarget(
        getPackageName(),
        foregroundPackage,
        node,
        Build.VERSION.SDK_INT >= 33 ? EditorInputMethod.current(this) : null);
  }

  private void refreshButton() {
    if (overlay == null || destroyed) return;
    boolean password = false, visible = false;
    if (!screenBlocked()) {
      try (EditorTarget target = target()) {
        password = target.password();
        visible =
            !password
                && (!termaiMicrophone || client != null || touching)
                && (target.available() || client != null || touching);
      }
    }
    if (password && client != null) {
      cancel(null);
      return;
    }
    controls.setVisibility(client != null ? View.VISIBLE : View.GONE);
    activity.waiting(waiting);
    activity.retryAvailable(client != null && client.canRetry());
    button.invalidate();
    button.setContentDescription(
        client != null && client.canRetry()
            ? "Retry saved dictation. Drag to move"
            : waiting
                ? "Transcribing. Drag to move"
                : client != null
                    ? "Stop dictation. Drag to move"
                    : "Dictate: hold and release, or tap to start and stop. Drag to move");
    updateBounds();
    layoutOverlay();
    if (visible && !added) {
      wm.addView(overlay, params);
      added = true;
    } else if (!visible && added) {
      wm.removeView(overlay);
      added = false;
    }
  }

  private void updateBounds() {
    if (Build.VERSION.SDK_INT >= 30) {
      WindowMetrics metrics = wm.getCurrentWindowMetrics();
      safeBounds.set(metrics.getBounds());
      android.graphics.Insets insets =
          metrics
              .getWindowInsets()
              .getInsetsIgnoringVisibility(
                  WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
      safeBounds.left += insets.left;
      safeBounds.top += insets.top;
      safeBounds.right -= insets.right;
      safeBounds.bottom -= insets.bottom;
    } else {
      android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
      wm.getDefaultDisplay().getRealMetrics(metrics);
      WindowInsets insets = overlay.getRootWindowInsets();
      safeBounds.set(
          insets == null ? 0 : insets.getStableInsetLeft(),
          insets == null ? dp(28) : insets.getStableInsetTop(),
          metrics.widthPixels - (insets == null ? 0 : insets.getStableInsetRight()),
          metrics.heightPixels - (insets == null ? dp(24) : insets.getStableInsetBottom()));
    }
    safeBounds.inset(dp(12), dp(12));
    visibleBottom = safeBounds.bottom;
    for (AccessibilityWindowInfo window : getWindows()) {
      if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
        Rect r = new Rect();
        window.getBoundsInScreen(r);
        if (!r.isEmpty() && r.top > safeBounds.top && Rect.intersects(safeBounds, r))
          visibleBottom = Math.min(visibleBottom, r.top - dp(12));
      }
      window.recycle();
    }
  }

  private void layoutOverlay() {
    if (overlay == null || destroyed || safeBounds.isEmpty()) return;
    OverlayPosition.Placement p =
        position.place(
            safeBounds.left,
            safeBounds.top,
            safeBounds.right,
            safeBounds.bottom,
            visibleBottom,
            dp(48),
            dp(146),
            dp(6),
            client != null);
    placeChild(button, p.buttonX, p.buttonY);
    placeChild(controls, p.panelX, p.panelY);
    controls.setLayoutDirection(
        p.panelOnLeft ? View.LAYOUT_DIRECTION_LTR : View.LAYOUT_DIRECTION_RTL);
    if (params.x == p.x && params.y == p.y && params.width == p.width && params.height == p.height)
      return;
    params.x = p.x;
    params.y = p.y;
    params.width = p.width;
    params.height = p.height;
    if (added) wm.updateViewLayout(overlay, params);
  }

  private void placeChild(View child, int x, int y) {
    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
    if (lp.leftMargin != x || lp.topMargin != y) {
      lp.leftMargin = x;
      lp.topMargin = y;
      child.setLayoutParams(lp);
    }
  }

  private void beginButtonTouch() {
    touching = true;
    int[] location = new int[2];
    button.getLocationOnScreen(location);
    dragOriginX = location[0];
    dragOriginY = location[1];
  }

  private void dragButton(float dx, float dy) {
    position.moveTo(
        dragOriginX + dx,
        dragOriginY + dy,
        safeBounds.left,
        safeBounds.top,
        safeBounds.right,
        safeBounds.bottom,
        visibleBottom,
        dp(48));
    if (!layoutPending && added) {
      layoutPending = true;
      overlay.postOnAnimation(layoutFrame);
    }
  }

  private void endButtonTouch(boolean moved) {
    touching = false;
    if (destroyed) return;
    overlay.removeCallbacks(layoutFrame);
    layoutPending = false;
    if (moved)
      Settings.prefs(this)
          .edit()
          .putFloat("button_x", position.x())
          .putFloat("button_y", position.y())
          .apply();
    refreshButton();
  }

  @Override
  public void onConfigurationChanged(android.content.res.Configuration config) {
    super.onConfigurationChanged(config);
    if (button != null) button.abandonTouch();
    refreshButton();
  }

  private void deliver(String text) {
    boolean inserted = false;
    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
    if (!locked()) {
      try (EditorTarget target = target()) {
        inserted = target.insert(text, clipboard);
      }
    }
    if (!inserted) {
      clipboard.setPrimaryClip(ClipData.newPlainText("Voxtype", text));
      toast("Transcript copied");
    }
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    // A foreground app event after unlock also recovers if the device did not
    // deliver ACTION_USER_PRESENT to this bound accessibility service.
    if (power != null && power.isInteractive() && !locked() && screenLock.userPresent())
      main.postDelayed(this::deliverAfterUnlock, 300);
    scheduleRefresh();
  }

  private void scheduleRefresh() {
    // Coalesce without postponing forever when a browser streams frequent content updates.
    if (!main.hasCallbacks(refresh)) main.postDelayed(refresh, 60);
  }

  @Override
  public void onInterrupt() {
    // Accessibility feedback can be interrupted when the keyguard takes focus.
    // The foreground recording remains under the user's explicit X/finish control.
    if (button != null) button.abandonTouch();
    refreshButton();
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    if (button != null) button.cancelTouch();
    if (overlay != null) overlay.removeCallbacks(layoutFrame);
    cancel(null);
    main.removeCallbacksAndMessages(null);
    if (added) wm.removeView(overlay);
    try {
      unregisterReceiver(screenEvents);
    } catch (Exception ignored) {
    }
    current.clear();
    super.onDestroy();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void toast(String text) {
    Toast.makeText(this, text, Toast.LENGTH_LONG).show();
  }
}
