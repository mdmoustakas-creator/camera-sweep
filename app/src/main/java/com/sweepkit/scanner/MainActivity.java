package com.sweepkit.scanner;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int BG = 0xFFF6F2EE, PANEL = 0xFFFFFFFF, INK = 0xFF1B1419, MUTE = 0xFF6B5F66,
            LINE = 0xFFE4DAD6, RED = 0xFFD42020, AMBER = 0xFFC77700, REDSOFT = 0xFFFDE3DF, AMBERSOFT = 0xFFFFF1D6;
    private static final int REQ_BLE = 7;

    private LinearLayout results;
    private TextView status, tips;
    private Button wifiBtn, bleBtn;
    private NetScanner net;
    private BleScanner ble;

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(20), dp(18), dp(32));
        scroll.addView(col);

        TextView title = text("Camera Sweep", 28, INK, true);
        col.addView(title);
        TextView sub = text("Check this home's Wi-Fi and nearby Bluetooth for hidden cameras and trackers.", 15, MUTE, false);
        sub.setPadding(0, dp(4), 0, dp(14));
        col.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        wifiBtn = button("Scan Wi-Fi", true);
        bleBtn = button("Scan Bluetooth", false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.setMarginEnd(dp(10));
        row.addView(wifiBtn, lp);
        row.addView(bleBtn, new LinearLayout.LayoutParams(0, dp(56), 1f));
        col.addView(row);

        status = text("Join the home's Wi-Fi, then tap Scan Wi-Fi. For Bluetooth, walk slowly around each room while it scans.", 15, INK, false);
        status.setPadding(0, dp(16), 0, dp(6));
        col.addView(status);

        tips = text("", 13, MUTE, false);
        tips.setVisibility(View.GONE);
        col.addView(tips);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        col.addView(results);

        TextView foot = text("Not every camera can be found this way. Cameras on their own network, on cellular data, or recording only to a memory card won't appear. "
                + "Pair this with a flashlight lens check. If you find a camera, don't touch it: photograph it and report it to the host, platform or police.", 13, MUTE, false);
        foot.setPadding(0, dp(18), 0, 0);
        col.addView(foot);

        setContentView(scroll);

        wifiBtn.setOnClickListener(v -> startWifi());
        bleBtn.setOnClickListener(v -> {
            if (ble != null && ble.isRunning()) ble.stop();
            else if (hasBlePermissions()) startBle();
            else requestPermissions(blePermissions(), REQ_BLE);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ble != null && ble.isRunning()) ble.stop();
    }

    // ---------- Wi-Fi ----------

    private void startWifi() {
        if (net != null && net.isRunning()) return;
        if (ble != null && ble.isRunning()) ble.stop();
        results.removeAllViews();
        tips.setVisibility(View.GONE);
        wifiBtn.setEnabled(false);
        wifiBtn.setText("Scanning…");
        status.setText("Starting Wi-Fi scan. This takes about a minute.");
        net = new NetScanner(this, new NetScanner.Listener() {
            @Override public void onProgress(String message) {
                runOnUiThread(() -> status.setText(message));
            }

            @Override public void onDone(List<NetScanner.NetDevice> devices, String summary) {
                runOnUiThread(() -> showWifi(devices, summary));
            }
        });
        net.start();
    }

    private void showWifi(List<NetScanner.NetDevice> devices, String summary) {
        wifiBtn.setEnabled(true);
        wifiBtn.setText("Scan Wi-Fi again");
        status.setText(summary);
        tips.setText("Red = looks like a camera. Amber = worth a look. To find one physically, check the home's router app for its name, or unplug gadgets one at a time and rescan.");
        tips.setVisibility(devices.isEmpty() ? View.GONE : View.VISIBLE);
        results.removeAllViews();
        for (NetScanner.NetDevice d : devices) {
            String head = !d.description.isEmpty() ? d.description
                    : !d.hostname.isEmpty() ? d.hostname
                    : !d.kind.isEmpty() ? d.kind : "Unknown device";
            List<String> lines = new ArrayList<>();
            if (!d.kind.isEmpty() && !head.equals(d.kind)) lines.add(d.kind);
            lines.addAll(d.reasons);
            StringBuilder meta = new StringBuilder("IP " + d.ip);
            if (!d.hostname.isEmpty() && !head.equals(d.hostname)) meta.append(" · ").append(d.hostname);
            if (!d.openPorts.isEmpty()) meta.append(" · open ports ").append(d.openPorts.toString().replaceAll("[\\[\\]]", ""));
            results.addView(card(head, lines, meta.toString(), d.level));
        }
    }

    // ---------- Bluetooth ----------

    private String[] blePermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{"android.permission.BLUETOOTH_SCAN", "android.permission.ACCESS_FINE_LOCATION"};
        }
        return new String[]{"android.permission.ACCESS_FINE_LOCATION"};
    }

    private boolean hasBlePermissions() {
        for (String p : blePermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_BLE) return;
        if (hasBlePermissions()) startBle();
        else status.setText("Bluetooth scanning needs the \"Nearby devices\" and \"Location\" permissions. "
                + "Android requires location for Bluetooth scans; this app never reads or stores where you are.");
    }

    private void startBle() {
        if (net != null && net.isRunning()) {
            status.setText("Wait for the Wi-Fi scan to finish first.");
            return;
        }
        results.removeAllViews();
        tips.setText("Walk slowly around the room. A device whose signal says \"getting stronger\" as you approach a spot is coming from near there. "
                + "If nothing shows up at all, make sure Location is turned on in quick settings.");
        tips.setVisibility(View.VISIBLE);
        ble = new BleScanner(this, new BleScanner.Listener() {
            @Override public void onUpdate(List<BleScanner.BleDevice> devices, int secondsLeft) {
                status.setText(devices.size() + " Bluetooth devices nearby · " + secondsLeft + "s left");
                showBle(devices);
            }

            @Override public void onError(String message) {
                status.setText(message);
                bleBtn.setText("Scan Bluetooth");
            }

            @Override public void onStopped(List<BleScanner.BleDevice> devices) {
                bleBtn.setText("Scan Bluetooth again");
                int flagged = 0;
                for (BleScanner.BleDevice d : devices) if (d.level > 0) flagged++;
                status.setText(devices.size() + " Bluetooth devices were nearby. "
                        + (flagged == 0 ? "None looked like a camera or tracker." : flagged + " worth a look (listed first)."));
                showBle(devices);
            }
        });
        ble.start();
        if (ble.isRunning()) {
            bleBtn.setText("Stop");
            status.setText("Listening for Bluetooth devices…");
        }
    }

    private void showBle(List<BleScanner.BleDevice> devices) {
        results.removeAllViews();
        for (BleScanner.BleDevice d : devices) {
            String head = !d.name.isEmpty() ? d.name : !d.kind.isEmpty() ? d.kind : "Unnamed device";
            List<String> lines = new ArrayList<>();
            if (!d.kind.isEmpty() && !head.equals(d.kind)) lines.add(d.kind);
            lines.addAll(d.reasons);
            String trend = d.trend >= 4 ? " · getting stronger ↑" : d.trend <= -4 ? " · getting weaker ↓" : "";
            lines.add("Signal: " + BleScanner.distance(d.rssi) + trend);
            int level = d.level;
            if (level == 0 && d.name.isEmpty() && d.kind.isEmpty() && d.rssi >= -60) {
                lines.add("Strong signal from an unidentified device. Look around nearby.");
                level = 1;
            }
            results.addView(card(head, lines, d.address + " · " + d.rssi + " dBm", level));
        }
    }

    // ---------- UI helpers ----------

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (primary) {
            bg.setColor(RED);
            b.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(PANEL);
            bg.setStroke(dp(2), RED);
            b.setTextColor(RED);
        }
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }

    private View card(String head, List<String> lines, String meta, int level) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(level == 2 ? REDSOFT : level == 1 ? AMBERSOFT : PANEL);
        bg.setStroke(dp(level > 0 ? 2 : 1), level == 2 ? RED : level == 1 ? AMBER : LINE);
        c.setBackground(bg);

        if (level > 0) {
            TextView tag = text(level == 2 ? "Possible camera" : "Worth a look", 12, level == 2 ? RED : AMBER, true);
            c.addView(tag);
        }
        TextView h = text(head, 17, INK, true);
        c.addView(h);
        for (String l : lines) {
            TextView t = text("• " + l, 14, INK, false);
            t.setPadding(0, dp(3), 0, 0);
            c.addView(t);
        }
        TextView m = text(meta, 12, MUTE, false);
        m.setPadding(0, dp(6), 0, 0);
        c.addView(m);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        c.setLayoutParams(lp);
        c.setGravity(Gravity.START);
        return c;
    }
}
