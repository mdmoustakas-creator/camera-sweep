package com.sweepkit.scanner;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/** Live Bluetooth Low Energy scan that flags camera-like devices and trackers. */
public class BleScanner {

    public interface Listener {
        void onUpdate(List<BleDevice> devices, int secondsLeft);
        void onError(String message);
        void onStopped(List<BleDevice> devices);
    }

    public static class BleDevice {
        public final String address;
        public String name = "";
        public String kind = "";
        public int rssi;
        public int refRssi;          // signal a few seconds ago, for the trend
        public int trend;            // + getting stronger, - weaker
        public long lastSeen;
        public int level = 0;
        public final Set<String> reasons = new LinkedHashSet<>();
        boolean fresh = true;

        BleDevice(String address) { this.address = address; }
    }

    private static final int SCAN_SECONDS = 120;
    private static final Map<Integer, String> COMPANIES = new HashMap<>();
    static {
        COMPANIES.put(0x004C, "Apple device");
        COMPANIES.put(0x0006, "Microsoft / Windows device");
        COMPANIES.put(0x0075, "Samsung device");
        COMPANIES.put(0x00E0, "Google device");
        COMPANIES.put(0x0171, "Amazon device");
        COMPANIES.put(0x038F, "Xiaomi device");
        COMPANIES.put(0x0157, "Amazfit / Zepp wearable");
        COMPANIES.put(0x0087, "Garmin device");
        COMPANIES.put(0x0059, "Nordic chip (generic gadget)");
        COMPANIES.put(0x02E5, "Espressif ESP chip");
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, BleDevice> devices = new HashMap<>();
    private BluetoothLeScanner scanner;
    private boolean running = false;
    private int ticks = 0;

    public BleScanner(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() { return running; }

    public void start() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) { listener.onError("This phone doesn't have Bluetooth."); return; }
        if (!adapter.isEnabled()) { listener.onError("Turn Bluetooth on in your phone's settings, then tap Scan again."); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { listener.onError("Bluetooth isn't ready yet. Try again in a few seconds."); return; }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(null, settings, callback);
        } catch (SecurityException e) {
            listener.onError("Bluetooth permission was denied. Allow \"Nearby devices\" for Camera Sweep in Settings.");
            return;
        }
        synchronized (devices) { devices.clear(); }
        running = true;
        ticks = 0;
        ui.postDelayed(tick, 1000);
    }

    public void stop() {
        if (!running) return;
        running = false;
        ui.removeCallbacks(tick);
        try { if (scanner != null) scanner.stopScan(callback); } catch (Exception ignored) { }
        listener.onStopped(snapshot());
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            ticks++;
            if (ticks % 3 == 0) {
                synchronized (devices) {
                    for (BleDevice d : devices.values()) {
                        d.trend = d.rssi - d.refRssi;
                        d.refRssi = d.rssi;
                    }
                }
            }
            if (ticks >= SCAN_SECONDS) { stop(); return; }
            listener.onUpdate(snapshot(), SCAN_SECONDS - ticks);
            ui.postDelayed(this, 1000);
        }
    };

    private List<BleDevice> snapshot() {
        long now = System.currentTimeMillis();
        List<BleDevice> list = new ArrayList<>();
        synchronized (devices) {
            for (BleDevice d : devices.values()) {
                if (now - d.lastSeen < 20000) list.add(d);
            }
        }
        list.sort((a, b) -> a.level != b.level ? b.level - a.level : b.rssi - a.rssi);
        return list;
    }

    private final ScanCallback callback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult r) { handle(r); }

        @Override public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) handle(r);
        }

        @Override public void onScanFailed(int errorCode) {
            ui.post(() -> {
                running = false;
                ui.removeCallbacks(tick);
                listener.onError("Bluetooth scan failed (code " + errorCode + "). Turn Bluetooth off and on, then try again.");
            });
        }
    };

    private void handle(ScanResult r) {
        String addr = r.getDevice().getAddress();
        synchronized (devices) {
            BleDevice d = devices.get(addr);
            if (d == null) { d = new BleDevice(addr); devices.put(addr, d); }
            int rssi = r.getRssi();
            if (d.fresh) { d.rssi = rssi; d.refRssi = rssi; d.fresh = false; }
            else d.rssi = (d.rssi * 2 + rssi) / 3;   // smooth jumpy readings
            d.lastSeen = System.currentTimeMillis();
            ScanRecord rec = r.getScanRecord();
            if (rec != null) classify(d, rec);
        }
    }

    private static int uuid16(ParcelUuid p) {
        String s = p.getUuid().toString();
        if (!s.endsWith("-0000-1000-8000-00805f9b34fb")) return -1;
        try { return Integer.parseInt(s.substring(4, 8), 16); } catch (Exception e) { return -1; }
    }

    private static void raise(BleDevice d, int level, String reason) {
        d.reasons.add(reason);
        if (level > d.level) d.level = level;
    }

    static void classify(BleDevice d, ScanRecord rec) {
        String n = rec.getDeviceName();
        if (n != null && !n.trim().isEmpty()) d.name = n.trim();

        Matcher cam = NetScanner.CAMERA_WORDS.matcher(d.name.toLowerCase(Locale.US));
        if (cam.find()) raise(d, 2, "Its name mentions \"" + cam.group(1).trim() + "\"");

        SparseArray<byte[]> msd = rec.getManufacturerSpecificData();
        if (msd != null) {
            for (int i = 0; i < msd.size(); i++) {
                int company = msd.keyAt(i);
                byte[] data = msd.valueAt(i);
                String k = COMPANIES.get(company);
                if (k != null && d.kind.isEmpty()) d.kind = k;
                if (company == 0x004C && data != null && data.length > 0 && data[0] == 0x12) {
                    d.kind = "Apple Find My accessory";
                    raise(d, 1, "Could be an AirTag or other Find My tracker away from its owner");
                }
                if (company == 0x02E5) raise(d, 1, "ESP chip, common in cheap and DIY cameras");
            }
        }

        Set<Integer> uuids = new LinkedHashSet<>();
        List<ParcelUuid> list = rec.getServiceUuids();
        if (list != null) for (ParcelUuid p : list) uuids.add(uuid16(p));
        Map<ParcelUuid, byte[]> sd = rec.getServiceData();
        if (sd != null) for (ParcelUuid p : sd.keySet()) uuids.add(uuid16(p));

        if (uuids.contains(0xFEED) || uuids.contains(0xFEEC) || uuids.contains(0xFD84)) {
            d.kind = "Tile tracker";
            raise(d, 1, "Bluetooth item tracker");
        }
        if (uuids.contains(0xFD5A)) {
            d.kind = "Samsung SmartTag";
            raise(d, 1, "Bluetooth item tracker");
        }
        if (uuids.contains(0xFD50)) raise(d, 1, "Tuya smart-home device (many cheap cameras run on Tuya)");
    }

    /** Plain-language distance hint from signal strength. */
    public static String distance(int rssi) {
        if (rssi >= -55) return "very close (about 1–2 m)";
        if (rssi >= -70) return "nearby (likely this room)";
        if (rssi >= -85) return "farther away";
        return "far or behind walls";
    }
}
