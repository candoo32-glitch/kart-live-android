package com.kartgarden.live;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import com.kartgarden.live.protocol.*;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity
    implements TextureView.SurfaceTextureListener,
        android.hardware.input.InputManager.InputDeviceListener {
  private static final int IMPORT_CLIENT = 1, IMPORT_CA = 2, EXPORT_CSV = 3;
  private static final int BG = 0xff0b1118,
      CARD = 0xff19212b,
      MINT = 0xff55d5ed,
      TEXT = 0xffedf2f7,
      MUTED = 0xff9aa8b7;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService files = Executors.newSingleThreadExecutor();
  private KartSession session;
  private CredentialVault vault;
  private ClientConfig config;
  private android.content.SharedPreferences prefs;
  private LinearLayout root, header, sidebar, footer;
  private FrameLayout camera;
  private TextureView texture;
  private Surface surface;
  private TextView title, health, videoText, empty, stats, telemetry, recordText;
  private Button connect, arm, record;
  private InstrumentView instrument, kartModel;
  private JoystickView joystick;
  private boolean resumed, fullScreen, controls = true, physicalHeld;
  private int physicalDevice = -1;
  private android.hardware.input.InputManager inputManager;
  private LinearLayout driveButtons;
  private float physicalX, physicalY;
  private int mode = Orientation.FUSION;
  private String host, profile;
  private float brightness = 0, contrast = 1, saturation = 1;
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          if (!resumed) return;
          update();
          handler.postDelayed(this, 100);
        }
      };

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    prefs = getSharedPreferences("preferences", MODE_PRIVATE);
    host = prefs.getString("host", "kart400.local");
    profile = prefs.getString("profile", "");
    vault = new CredentialVault(this);
    session = new KartSession(getCacheDir());
    try {
      config = vault.load();
    } catch (Exception e) {
      notifyError("Saved enrollment could not be loaded. Import it again.");
    }
    build();
    inputManager = (android.hardware.input.InputManager) getSystemService(INPUT_SERVICE);
    inputManager.registerInputDeviceListener(this, handler);
  }

  private int dp(float x) {
    return Math.round(x * getResources().getDisplayMetrics().density);
  }

  private android.graphics.drawable.GradientDrawable rounded(int color) {
    var d = new android.graphics.drawable.GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(14));
    return d;
  }

  private LinearLayout row() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  private LinearLayout column() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private TextView label(String s, int size, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    return t;
  }

  private Button button(String s, Runnable action) {
    Button b = new Button(this);
    b.setText(s);
    b.setTextSize(12);
    b.setTextColor(TEXT);
    b.setAllCaps(false);
    b.setMinHeight(dp(44));
    b.setMinimumHeight(dp(44));
    b.setPadding(dp(9), 0, dp(9), 0);
    b.setBackground(rounded(0xff243342));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private void addButton(LinearLayout r, Button b) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
    lp.setMargins(dp(3), dp(2), dp(3), dp(2));
    r.addView(b, lp);
  }

  private void build() {
    root = column();
    root.setBackgroundColor(BG);
    root.setPadding(dp(12), dp(8), dp(12), dp(8));
    setContentView(root);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            var bars = insets.getInsets(WindowInsets.Type.systemBars());
            root.setPadding(
                dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, dp(8) + bars.bottom);
          } else {
            root.setPadding(
                dp(12) + insets.getSystemWindowInsetLeft(),
                dp(8) + insets.getSystemWindowInsetTop(),
                dp(12) + insets.getSystemWindowInsetRight(),
                dp(8) + insets.getSystemWindowInsetBottom());
          }
          return insets;
        });
    header = row();
    title = label("KART LIVE", 18, TEXT);
    title.setTypeface(null, Typeface.BOLD);
    title.setLetterSpacing(.08f);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    record = button("Diagnostics", this::toggleRecording);
    addButton(header, record);
    addButton(header, button("Share CSV", this::exportCsv));
    addButton(header, button("Network", this::setup));
    connect = button("Connect", this::toggleConnection);
    connect.setBackground(rounded(0xff087f9a));
    addButton(header, connect);
    root.addView(header);
    health = label("OFFLINE · Import client enrollment and gateway CA in Network", 11, MUTED);
    health.setMaxLines(1);
    health.setEllipsize(android.text.TextUtils.TruncateAt.END);
    health.setPadding(dp(3), dp(3), 0, dp(6));
    root.addView(health);
    LinearLayout body = row();
    body.setGravity(Gravity.TOP);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout videoColumn = column();
    body.addView(videoColumn, new LinearLayout.LayoutParams(0, -1, 1));
    camera = new FrameLayout(this);
    camera.setBackground(rounded(0xff090e16));
    camera.setClipToOutline(true);
    videoColumn.addView(camera, new LinearLayout.LayoutParams(-1, 0, 1));
    texture = new TextureView(this);
    texture.setSurfaceTextureListener(this);
    camera.addView(texture, new FrameLayout.LayoutParams(-1, -1));
    texture.setOnClickListener(v -> fullScreen());
    empty = label("Waiting for camera", 16, MUTED);
    empty.setBackgroundColor(0xff090e16);
    empty.setGravity(Gravity.CENTER);
    camera.addView(empty, new FrameLayout.LayoutParams(-1, -1));
    empty.setOnClickListener(v -> fullScreen());
    videoText = label("CAMERA · waiting for stream", 10, TEXT);
    videoText.setPadding(dp(10), dp(8), dp(10), dp(8));
    videoText.setBackgroundColor(0x880b1118);
    camera.addView(videoText, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
    joystick = new JoystickView(this);
    joystick.listener((x, y) -> session.input(x, y));
    FrameLayout.LayoutParams joy =
        new FrameLayout.LayoutParams(dp(112), dp(112), Gravity.BOTTOM | Gravity.START);
    joy.setMargins(dp(12), 0, 0, dp(12));
    camera.addView(joystick, joy);
    // A fullscreen affordance stays inside the camera in both layouts.
    Button view = button("View", this::fullScreen);
    FrameLayout.LayoutParams viewPosition =
        new FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP | Gravity.END);
    viewPosition.setMargins(0, dp(34), dp(8), 0);
    camera.addView(view, viewPosition);
    driveButtons = row();
    addButton(driveButtons, button("A", this::confirmArm));
    Button b = button("B", this::emergencyStop);
    b.setBackground(rounded(0xff963d46));
    addButton(driveButtons, b);
    FrameLayout.LayoutParams drivePosition =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
    drivePosition.setMargins(0, 0, dp(12), dp(12));
    camera.addView(driveButtons, drivePosition);
    footer = row();
    arm = button("ARM", this::confirmArm);
    arm.setBackground(rounded(0xffaa651a));
    addButton(footer, arm);
    Button stop = button("EMERGENCY STOP", this::emergencyStop);
    stop.setBackground(rounded(0xffae3445));
    addButton(footer, stop);
    recordText = label("DISARMED", 10, MUTED);
    footer.addView(recordText, new LinearLayout.LayoutParams(0, -2, 1));
    addButton(footer, button("Video Controls", this::videoAdjustments));
    addButton(footer, button("Hide Controls", this::toggleControls));
    HorizontalScrollView controlStrip = new HorizontalScrollView(this);
    controlStrip.setHorizontalScrollBarEnabled(false);
    controlStrip.addView(footer);
    videoColumn.addView(controlStrip, new LinearLayout.LayoutParams(-1, dp(48)));
    sidebar = column();
    int screenWidth =
        (int)
            (getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
    int sideWidth = Math.max(180, Math.min(255, Math.round(screenWidth * .22f)));
    LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(dp(sideWidth), -1);
    side.setMargins(dp(8), 0, 0, 0);
    body.addView(sidebar, side);
    ScrollView gaugeScroll = new ScrollView(this);
    gaugeScroll.setFillViewport(false);
    gaugeScroll.setVerticalScrollBarEnabled(false);
    LinearLayout gauges = column();
    gauges.setBackground(rounded(CARD));
    instrument = new InstrumentView(this);
    gauges.addView(instrument, new LinearLayout.LayoutParams(-1, dp(350)));
    Button cal = button("Calibrate all", this::calibrate);
    LinearLayout.LayoutParams calParams = new LinearLayout.LayoutParams(-1, dp(40));
    calParams.setMargins(dp(16), 0, dp(16), dp(6));
    gauges.addView(cal, calParams);
    telemetry = label("Battery —   Wi-Fi —", 10, MUTED);
    telemetry.setGravity(Gravity.CENTER);
    telemetry.setPadding(dp(6), dp(4), dp(6), dp(8));
    gauges.addView(telemetry);
    Button advanced = button("Advanced diagnostics", this::advancedDiagnostics);
    gauges.addView(advanced, new LinearLayout.LayoutParams(-1, dp(40)));
    gaugeScroll.addView(gauges);
    sidebar.addView(gaugeScroll, new LinearLayout.LayoutParams(-1, 0, 3));
    kartModel = new InstrumentView(this);
    kartModel.modelOnly();
    LinearLayout.LayoutParams modelParams = new LinearLayout.LayoutParams(-1, 0, 2);
    modelParams.setMargins(0, dp(6), 0, 0);
    sidebar.addView(kartModel, modelParams);
    stats = label("No stream", 10, MUTED);
    update();
  }

  private void toggleControls() {
    controls = !controls;
    emergencyStop();
    joystick.setVisibility(controls ? View.VISIBLE : View.GONE);
    driveButtons.setVisibility(controls ? View.VISIBLE : View.GONE);
    Button toggle = findControlToggle(footer);
    if (toggle != null) toggle.setText(controls ? "Hide Controls" : "Show Controls");
  }

  private Button findControlToggle(LinearLayout layout) {
    for (int i = 0; i < layout.getChildCount(); i++) {
      View child = layout.getChildAt(i);
      if (child instanceof Button b
          && (b.getText().equals("Hide Controls") || b.getText().equals("Show Controls"))) return b;
    }
    return null;
  }

  private void advancedDiagnostics() {
    new AlertDialog.Builder(this)
        .setTitle("Advanced diagnostics")
        .setItems(
            new String[] {"Orientation source", "Connection and IMU details"},
            (dialog, which) -> {
              if (which == 0) selectSource();
              else details();
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void update() {
    KartSession.Connection c = session.current();
    if (joystick.held() && hasWindowFocus()) session.input(joystick.steer(), joystick.throttle());
    else if (physicalHeld && hasWindowFocus()) session.input(physicalX, physicalY);
    connect.setText(c != null && c.alive ? "Disconnect" : "Connect");
    if (c == null) {
      arm.setText("ARM");
      arm.setBackground(rounded(0xff243342));
      telemetry.setText("Battery —   Wi-Fi —\nIMU waiting");
      instrument.update(
          new Orientation.Snapshot(
              "Fusion",
              "WAIT_SOURCE",
              "Waiting for IMU",
              null,
              null,
              "REQUIRED",
              "WAIT_BIAS",
              "NOT_EVALUATED",
              0,
              0),
          false);
      kartModel.update(
          new Orientation.Snapshot(
              "Fusion",
              "WAIT_SOURCE",
              "Waiting for IMU",
              null,
              null,
              "REQUIRED",
              "WAIT_BIAS",
              "NOT_EVALUATED",
              0,
              0),
          false);
      videoText.setText("CAMERA · waiting for stream");
      title.setText("KART LIVE");
      health.setText(
          config == null
              ? "Import client JSON and the public gateway CA in Network"
              : "Ready · " + host + " · " + config.permission() + " permission");
      stats.setText("No stream");
      recordText.setText("DISARMED");
      empty.setVisibility(View.VISIBLE);
      return;
    }
    boolean fresh = KartSession.age(c.lastImu) < 1000,
        statusFresh = KartSession.age(c.lastStatus) < 1800;
    title.setText("KART LIVE");
    String bars = "—";
    Integer rssi = statusFresh ? c.rssi : null;
    if (rssi != null) {
      int n = rssi >= -55 ? 4 : rssi >= -67 ? 3 : rssi >= -75 ? 2 : rssi >= -85 ? 1 : 0;
      bars = "▂▄▆█".substring(0, n) + " " + rssi + " dBm";
    }
    health.setText(
        !c.error.isEmpty()
            ? c.error
            : (statusFresh ? c.health : "status stale") + " · " + c.serial + " · " + c.profileId);
    health.setTextColor(c.error.isEmpty() && c.connected && statusFresh ? MINT : 0xffffc977);
    String battery =
        c.battery < 0
            ? "—"
            : "["
                + "●●●●".substring(0, c.battery)
                + "○○○○".substring(0, 4 - c.battery)
                + "]"
                + (c.cable ? " cable" : "");
    telemetry.setText(
        "Battery "
            + battery
            + "   Wi-Fi "
            + bars
            + "\n"
            + (fresh ? "IMU live" : "IMU stale")
            + " · "
            + c.telemetryPackets
            + " packets");
    synchronized (c.orientation) {
      c.instrumentMode = mode;
      Orientation.Snapshot snapshot = c.orientation.snapshot(c.latest, mode, System.nanoTime());
      instrument.update(snapshot, fresh);
      kartModel.update(snapshot, fresh);
    }
    long videoAge = KartSession.age(c.lastVideo);
    boolean noPicture = videoAge > 1500 || session.decoder.rendered == 0;
    empty.setVisibility(noPicture ? View.VISIBLE : View.GONE);
    if (noPicture) empty.setText("Waiting for camera");
    videoText.setText(c.name + " · " + (videoAge > 1500 ? "video stale" : session.decoder.detail));
    stats.setText(
        c.videoPackets
            + " UDP · "
            + c.frames
            + " frames · "
            + c.gaps
            + " gaps\n"
            + String.format(
                Locale.ROOT,
                "%.1f MB · %d decode errors",
                c.videoBytes / 1e6,
                session.decoder.errors));
    arm.setText(c.armed ? "DISARM" : "ARM");
    arm.setBackground(rounded(c.armed ? 0xff795832 : 0xff243342));
    record.setText(session.recorder.active() ? "Stop Recording" : "Diagnostics");
    recordText.setText(c.armed ? "ARMED" : "DISARMED");
  }

  private void toggleConnection() {
    if (session.current() != null && session.current().alive) {
      session.disconnect();
      joystick.neutral();
      physicalHeld = false;
      update();
      return;
    }
    if (config == null || !vault.hasCa()) {
      setup();
      return;
    }
    physicalHeld = false;
    joystick.neutral();
    mode = Orientation.FUSION;
    session.connect(config, host, profile, vault);
    update();
  }

  private void confirmArm() {
    if (!controls) {
      toast("Show controls in Network before arming");
      return;
    }
    if (session.current() != null && session.current().armed) {
      emergencyStop();
      return;
    }
    String reason = session.armBlock();
    if (!reason.isEmpty()) {
      toast(reason);
      return;
    }
    session.input(0, 0);
    new AlertDialog.Builder(this)
        .setTitle("Authorize kart movement?")
        .setMessage(
            "The joystick will move the kart. Place it on a clear surface, release the controls,"
                + " then arm. B or STOP disarms immediately.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Arm", (d, w) -> session.arm())
        .show();
  }

  private void emergencyStop() {
    physicalHeld = false;
    joystick.neutral();
    session.stop("STOP · DISARMED");
    update();
  }

  private void calibrate() {
    emergencyStop();
    if (session.current() == null || KartSession.age(session.current().lastImu) >= 1000) {
      toast("Connect a kart with fresh IMU data first");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Calibrate all instruments")
        .setMessage(
            "Put this kart on a stable, level surface and keep it still. Calibration stays in this"
                + " session; it is never shared between karts.")
        .setPositiveButton("Calibrate all", (d, w) -> session.calibrate())
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void selectSource() {
    new AlertDialog.Builder(this)
        .setTitle("Advanced orientation diagnostics")
        .setSingleChoiceItems(
            new String[] {"Nintendo", "Gyroscope (experimental)", "Fusion (default)"},
            mode,
            (d, which) -> {
              mode = which;
              d.dismiss();
              if (which > 0)
                toast(
                    "Experimental: calibration required; axes and gravity correction need physical"
                        + " validation");
            })
        .show();
  }

  private void fullScreen() {
    fullScreen = !fullScreen;
    sidebar.setVisibility(fullScreen ? View.GONE : View.VISIBLE);
    header.setVisibility(fullScreen ? View.GONE : View.VISIBLE);
    health.setVisibility(fullScreen ? View.GONE : View.VISIBLE);
    ((View) footer.getParent()).setVisibility(fullScreen ? View.GONE : View.VISIBLE);
    texture.post(this::fitVideo);
  }

  private void setup() {
    emergencyStop();
    LinearLayout contents = column();
    contents.setPadding(dp(16), dp(8), dp(16), dp(8));
    TextView description =
        label(
            "Enroll this device in Kart Garden → Client access. Import its JSON and"
                + " openkart-ca.crt. Use a separate credential for each receiving device.",
            14,
            TEXT);
    contents.addView(description);
    EditText address = new EditText(this);
    address.setSingleLine();
    address.setText(host);
    address.setHint("Gateway hostname or IPv4 address");
    address.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    contents.addView(address);
    EditText chosen = new EditText(this);
    chosen.setSingleLine();
    chosen.setText(profile);
    chosen.setHint("Profile ID (blank = gateway’s active kart)");
    contents.addView(chosen);
    contents.addView(
        label(
            "Client: "
                + (config == null ? "not imported" : config.permission() + " · " + config.id())
                + "\nGateway CA: "
                + (vault.hasCa() ? "imported" : "not imported"),
            12,
            MUTED));
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Connect to your garden")
            .setView(contents)
            .setNegativeButton("Close", null)
            .setPositiveButton("Save address", null)
            .create();
    for (String item :
        new String[] {
          "Import client JSON",
          "Import gateway CA",
          "Choose saved kart",
          "Forget local enrollment",
          "Show / hide controls"
        }) {
      Button b =
          button(
              item,
              () -> {
                dialog.dismiss();
                switch (item) {
                  case "Import client JSON" -> pick(IMPORT_CLIENT);
                  case "Import gateway CA" -> pick(IMPORT_CA);
                  case "Choose saved kart" -> chooseKart();
                  case "Forget local enrollment" -> forget();
                  default -> {
                    controls = !controls;
                    joystick.setVisibility(controls ? View.VISIBLE : View.GONE);
                    driveButtons.setVisibility(controls ? View.VISIBLE : View.GONE);
                    emergencyStop();
                  }
                }
              });
      contents.addView(b, new LinearLayout.LayoutParams(-1, dp(48)));
    }
    ScrollView scroll = new ScrollView(this);
    contents.removeView(description);
    contents.addView(description, 0); // Keep instructions first when keyboard opens.
    // Use a scrolling dialog so focused fields remain reachable on small phones.
    dialog.setView(scroll);
    scroll.addView(contents);
    dialog.setOnShowListener(
        d -> {
          dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
          dialog
              .getButton(AlertDialog.BUTTON_POSITIVE)
              .setOnClickListener(
                  v -> {
                    String value = address.getText().toString().trim();
                    if (value.trim().isEmpty()
                        || value.contains("://")
                        || value.contains("/")
                        || value.contains(" ")
                        || value.contains("\n")) {
                      address.setError("Enter a hostname or IP, without scheme or port");
                      return;
                    }
                    session.disconnect();
                    host = value;
                    profile = chosen.getText().toString().trim();
                    prefs.edit().putString("host", host).putString("profile", profile).apply();
                    dialog.dismiss();
                    update();
                  });
        });
    dialog.show();
  }

  private void chooseKart() {
    KartSession.Connection c = session.current();
    if (c == null || c.rawStatus.isEmpty()) {
      toast("Connect first to load saved kart profiles");
      return;
    }
    try {
      org.json.JSONArray maps = new org.json.JSONObject(c.rawStatus).getJSONArray("port_map");
      String[] labels = new String[maps.length() + 1], ids = new String[labels.length];
      labels[0] = "Gateway’s active kart";
      ids[0] = "";
      for (int i = 0; i < maps.length(); i++) {
        org.json.JSONObject map = maps.getJSONObject(i), kart = map.optJSONObject("kart");
        ids[i + 1] = map.getString("id");
        labels[i + 1] =
            (kart == null ? ids[i + 1] : kart.optString("name", ids[i + 1])) + " · " + ids[i + 1];
      }
      new AlertDialog.Builder(this)
          .setTitle("Receive from kart")
          .setItems(
              labels,
              (d, n) -> {
                session.disconnect();
                profile = ids[n];
                prefs.edit().putString("profile", profile).apply();
                toggleConnection();
              })
          .show();
    } catch (Exception e) {
      notifyError("Could not read profile list");
    }
  }

  private void forget() {
    new AlertDialog.Builder(this)
        .setTitle("Forget this device’s enrollment?")
        .setMessage(
            "Deletes the local encrypted client key and CA. The client stays enrolled on the"
                + " gateway until its administrator revokes it.")
        .setPositiveButton(
            "Forget",
            (d, w) -> {
              session.disconnect();
              try {
                vault.clear();
                config = null;
                update();
              } catch (Exception e) {
                notifyError("Could not clear enrollment");
              }
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void pick(int code) {
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(i, code);
  }

  private void exportCsv() {
    emergencyStop();
    session.recorder.stop();
    try {
      session.recorder.export();
      Intent i =
          new Intent(Intent.ACTION_CREATE_DOCUMENT)
              .setType("text/csv")
              .addCategory(Intent.CATEGORY_OPENABLE)
              .putExtra(Intent.EXTRA_TITLE, "KartLive-Diagnostics.csv");
      startActivityForResult(i, EXPORT_CSV);
    } catch (Exception e) {
      notifyError(e.getMessage());
    }
  }

  private void toggleRecording() {
    if (session.recorder.active()) session.recorder.stop();
    else if (session.current() == null) toast("Connect before recording");
    else
      try {
        session.recorder.start();
      } catch (Exception e) {
        notifyError("Could not start recording");
      }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    session.disconnect();
    files.execute(
        () -> {
          try {
            String message;
            if (request == EXPORT_CSV) {
              try (InputStream in = new FileInputStream(session.recorder.export());
                  OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Destination unavailable");
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
              }
              message = "CSV exported";
            } else {
              byte[] bytes;
              try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("File unavailable");
                bytes = GatewayApi.readLimited(in, 65536);
              }
              if (request == IMPORT_CA) {
                vault.importCa(bytes);
                message = "Gateway CA imported";
              } else {
                String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                ClientConfig parsed = ClientConfig.parse(json);
                vault.save(json);
                runOnUiThread(
                    () -> {
                      config = parsed;
                      host = parsed.host();
                      prefs.edit().putString("host", host).apply();
                    });
                message = "Client enrolled · " + parsed.permission() + " permission";
              }
            }
            runOnUiThread(
                () -> {
                  toast(message);
                  update();
                });
          } catch (Exception e) {
            runOnUiThread(() -> notifyError("Import/export failed: " + e.getMessage()));
          }
        });
  }

  private void details() {
    emergencyStop();
    KartSession.Connection c = session.current();
    String text =
        "Android client 0.2.2\n\n"
            + session.decoder.detail
            + "\n"
            + session.recorder.state
            + "\n\n";
    if (c != null) {
      text +=
          c.control + "\nVideo age: " + age(c.lastVideo) + "\nIMU age: " + age(c.lastImu) + "\n";
      if (c.latest != null && c.latest.latest() != null) {
        Telemetry.Sample s = c.latest.latest();
        text +=
            "Accel (decoded m/s²): "
                + java.util.Arrays.toString(s.accel())
                + "\nGyro (°/s): "
                + java.util.Arrays.toString(s.gyro())
                + "\nFlags: "
                + c.latest.flags
                + "\n";
      }
      text += "\nGateway status (no keys):\n" + c.rawStatus;
    }
    TextView v = label(text, 12, TEXT);
    v.setTextIsSelectable(true);
    v.setPadding(dp(16), dp(10), dp(16), dp(10));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(v);
    new AlertDialog.Builder(this)
        .setTitle("Connection diagnostics")
        .setView(scroll)
        .setPositiveButton("Close", null)
        .show();
  }

  private String age(long t) {
    long a = KartSession.age(t);
    return a == Long.MAX_VALUE ? "unavailable" : a + " ms";
  }

  private void videoAdjustments() {
    LinearLayout view = column();
    view.setPadding(dp(16), dp(8), dp(16), dp(8));
    slider(
        view,
        "Brightness",
        Math.round((brightness + 1) * 100),
        200,
        v -> brightness = v / 100f - 1);
    slider(view, "Contrast", Math.round(contrast * 100), 200, v -> contrast = v / 100f);
    slider(view, "Saturation", Math.round(saturation * 100), 200, v -> saturation = v / 100f);
    new AlertDialog.Builder(this)
        .setTitle("Video appearance · display only")
        .setView(view)
        .setNegativeButton(
            "Reset",
            (d, w) -> {
              brightness = 0;
              contrast = saturation = 1;
              applyLook();
            })
        .setPositiveButton("Done", null)
        .show();
  }

  private void slider(
      LinearLayout view,
      String title,
      int progress,
      int max,
      java.util.function.IntConsumer change) {
    view.addView(label(title, 14, TEXT));
    SeekBar bar = new SeekBar(this);
    bar.setMax(max);
    bar.setProgress(progress);
    bar.setContentDescription(title);
    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {}

          public void onProgressChanged(SeekBar b, int n, boolean user) {
            if (user) {
              change.accept(n);
              applyLook();
            }
          }
        });
    view.addView(bar);
  }

  private void applyLook() {
    ColorMatrix matrix = new ColorMatrix();
    matrix.setSaturation(saturation);
    float offset = (1 - contrast) * 127.5f + brightness * 255;
    matrix.postConcat(
        new ColorMatrix(
            new float[] {
              contrast, 0, 0, 0, offset, 0, contrast, 0, 0, offset, 0, 0, contrast, 0, offset, 0, 0,
              0, 1, 0
            }));
    Paint paint = new Paint();
    paint.setColorFilter(new ColorMatrixColorFilter(matrix));
    texture.setLayerPaint(paint);
  }

  private void fitVideo() {
    if (texture.getWidth() == 0) return;
    float w = texture.getWidth(),
        h = texture.getHeight(),
        fitW = Math.min(w, h * 16 / 9),
        fitH = fitW * 9 / 16;
    Matrix m = new Matrix();
    m.setScale(fitW / w, fitH * 800 / 720 / h);
    m.postTranslate((w - fitW) / 2, (h - fitH) / 2);
    texture.setTransform(m);
  }

  @Override
  public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
    surface = new Surface(t);
    session.decoder.surface(surface);
    fitVideo();
    applyLook();
  }

  @Override
  public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {
    fitVideo();
  }

  @Override
  public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
    session.stop("Video surface lost · DISARMED");
    session.decoder.surface(null);
    if (surface != null) {
      surface.release();
      surface = null;
    }
    return true;
  }

  @Override
  public void onSurfaceTextureUpdated(SurfaceTexture t) {}

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    session.foreground(true);
    handler.post(refresh);
    root.post(
        () -> {
          if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            getWindow()
                .getInsetsController()
                .setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
          } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                decor.getSystemUiVisibility()
                    & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
          }
        });
  }

  @Override
  protected void onStop() {
    resumed = false;
    handler.removeCallbacks(refresh);
    physicalHeld = false;
    joystick.neutral();
    session.foreground(false);
    session.disconnect();
    super.onStop();
  }

  @Override
  public void onWindowFocusChanged(boolean focus) {
    super.onWindowFocusChanged(focus);
    if (session != null && !focus) {
      physicalHeld = false;
      joystick.neutral();
      session.stop("Focus lost · DISARMED");
    }
  }

  @Override
  protected void onDestroy() {
    inputManager.unregisterInputDeviceListener(this);
    session.close();
    files.shutdown();
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    emergencyStop();
    if (fullScreen) fullScreen();
    else super.onBackPressed();
  }

  @Override
  public boolean onGenericMotionEvent(MotionEvent e) {
    if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        && e.getAction() == MotionEvent.ACTION_MOVE
        && controls) {
      physicalDevice = e.getDeviceId();
      physicalX = e.getAxisValue(MotionEvent.AXIS_X);
      physicalY = -e.getAxisValue(MotionEvent.AXIS_Y);
      physicalHeld = Math.abs(physicalX) > .08 || Math.abs(physicalY) > .08;
      session.input(physicalX, physicalY);
      return true;
    }
    return super.onGenericMotionEvent(e);
  }

  @Override
  public boolean onKeyDown(int code, KeyEvent event) {
    if (code == KeyEvent.KEYCODE_BUTTON_B) {
      emergencyStop();
      return true;
    }
    if (code == KeyEvent.KEYCODE_BUTTON_A) {
      if (event.getRepeatCount() == 0) confirmArm();
      return true;
    }
    return super.onKeyDown(code, event);
  }

  public void onInputDeviceAdded(int id) {}

  public void onInputDeviceChanged(int id) {
    if (id == physicalDevice) emergencyStop();
  }

  public void onInputDeviceRemoved(int id) {
    if (id == physicalDevice) {
      physicalDevice = -1;
      emergencyStop();
    }
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }

  private void notifyError(String s) {
    new AlertDialog.Builder(this)
        .setTitle("Kart Live")
        .setMessage(s == null ? "Operation failed" : s)
        .setPositiveButton("OK", null)
        .show();
  }
}
