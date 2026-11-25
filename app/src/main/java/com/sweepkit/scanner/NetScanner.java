package com.sweepkit.scanner;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scans the local Wi-Fi network (/24) for devices and flags likely cameras. */
public class NetScanner {

    public interface Listener {
        void onProgress(String message);
        void onDone(List<NetDevice> devices, String summary);
    }

    public static class NetDevice {
        public final String ip;
        public String hostname = "";
        public final Set<Integer> openPorts = Collections.synchronizedSet(new TreeSet<>());
        public final Set<String> ssdpLocations = Collections.synchronizedSet(new LinkedHashSet<>());
        public String ssdpServer = "";
        public String description = "";   // from UPnP / ONVIF
        public boolean onvif = false;
        public boolean isSelf = false;
        public boolean isGateway = false;
        public String kind = "";
        public final List<String> reasons = new ArrayList<>();
        public int level = 0;              // 0 = info, 1 = worth a look, 2 = likely camera

        NetDevice(String ip) { this.ip = ip; }

        int lastOctet() {
            try { return Integer.parseInt(ip.substring(ip.lastIndexOf('.') + 1)); }
            catch (Exception e) { return 0; }
        }
    }

    // Ports probed on every host. Camera-specific ones come first.
    private static final int[] PORTS = {554, 8554, 37777, 34567, 8000, 80, 443, 8080, 8899, 6668, 1935, 9000, 62078, 8009};

    static final Pattern CAMERA_WORDS = Pattern.compile(
            "(cam|camera|ipc|wyze|hikvision|dahua|reolink|blink|eufy|arlo|tapo|imou|ezviz|foscam|amcrest|lorex|swann|annke|"
                    + "v380|yoosee|icsee|xmeye|\\bnvr\\b|\\bdvr\\b|\\bring\\b|nest ?cam|kasa ?cam|nanny|spy)");
    static final Pattern ESP_WORDS = Pattern.compile("(espressif|\\besp[-_]|esp32|esp8266)");

    private final Context ctx;
    private final Listener listener;
    private final ConcurrentHashMap<String, NetDevice> found = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public NetScanner(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() { return running; }

    public void start() {
        if (running) return;
        running = true;
        new Thread(this::run, "net-scan").start();
    }

    private NetDevice device(String ip) {
        return found.computeIfAbsent(ip, NetDevice::new);
    }

    private void run() {
        WifiManager.MulticastLock lock = null;
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm.getActiveNetwork();
            NetworkCapabilities caps = net == null ? null : cm.getNetworkCapabilities(net);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                finish("Connect this phone to the home's Wi-Fi first, then scan again.");
                return;
            }
            LinkProperties lp = cm.getLinkProperties(net);
            String myIp = null, gatewayIp = null;
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    if (la.getAddress() instanceof Inet4Address) myIp = la.getAddress().getHostAddress();
                }
                for (RouteInfo r : lp.getRoutes()) {
                    if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) {
                        gatewayIp = r.getGateway().getHostAddress();
                    }
                }
            }
            if (myIp == null) {
                finish("Couldn't read this phone's Wi-Fi address. Turn Wi-Fi off and on, then try again.");
                return;
            }
            String prefix = myIp.substring(0, myIp.lastIndexOf('.') + 1);

            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("camera-sweep");
                lock.setReferenceCounted(false);
                lock.acquire();
            }

            listener.onProgress("Asking devices to identify themselves…");
            Thread ssdp = new Thread(this::ssdpDiscover);
            Thread onvif = new Thread(this::onvifDiscover);
            ssdp.start();
            onvif.start();

            ExecutorService pool = Executors.newFixedThreadPool(48);
            AtomicInteger done = new AtomicInteger();
            for (int i = 1; i <= 254; i++) {
                final String ip = prefix + i;
                pool.submit(() -> {
                    probeHost(ip);
                    int n = done.incrementAndGet();
                    if (n % 10 == 0) {
                        listener.onProgress("Checked " + n + " of 254 addresses · " + found.size() + " devices so far");
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(4, TimeUnit.MINUTES);
            ssdp.join(8000);
            onvif.join(8000);

            listener.onProgress("Looking up device names…");
            ExecutorService names = Executors.newFixedThreadPool(16);
            for (NetDevice d : found.values()) {
                names.submit(() -> {
                    lookupName(d);
                    fetchUpnpDescription(d);
                });
            }
            names.shutdown();
            names.awaitTermination(25, TimeUnit.SECONDS);

            List<NetDevice> list = new ArrayList<>(found.values());
            int flagged = 0, maybe = 0;
            for (NetDevice d : list) {
                d.isSelf = d.ip.equals(myIp);
                d.isGateway = d.ip.equals(gatewayIp);
                classify(d);
                if (d.level == 2) flagged++;
                else if (d.level == 1) maybe++;
            }
            list.sort((a, b) -> a.level != b.level ? b.level - a.level : a.lastOctet() - b.lastOctet());

            String summary;
            if (flagged > 0) {
                summary = flagged + (flagged == 1 ? " device looks" : " devices look") + " like a camera. Find "
                        + (flagged == 1 ? "it" : "them") + " in the home before you relax.";
            } else if (maybe > 0) {
                summary = "No clear cameras, but " + maybe + (maybe == 1 ? " device is" : " devices are") + " worth a look.";
            } else {
                summary = "No camera-like devices found on this network. A camera on a separate network, cellular, or recording only to a memory card won't show up here.";
            }
            summary = list.size() + " devices found. " + summary;
            finish(list, summary);
        } catch (Exception e) {
            finish("Scan stopped: " + e.getMessage());
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
        }
    }

    private void finish(String message) { finish(new ArrayList<>(), message); }

    private void finish(List<NetDevice> list, String summary) {
        running = false;
        listener.onDone(list, summary);
    }

    /** A host is "alive" if any port accepts or actively refuses a connection, or it answers a ping. */
    private void probeHost(String ip) {
        boolean alive = false;
        List<Integer> open = new ArrayList<>();
        for (int port : PORTS) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, port), 300);
                open.add(port);
                alive = true;
            } catch (ConnectException e) {
                String m = String.valueOf(e.getMessage()).toLowerCase(Locale.US);
                if (m.contains("refused")) alive = true;
            } catch (SocketTimeoutException ignored) {
            } catch (IOException ignored) {
            }
        }
        if (!alive) {
            try { alive = InetAddress.getByName(ip).isReachable(400); } catch (IOException ignored) { }
        }
        if (alive || !open.isEmpty()) {
            NetDevice d = device(ip);
            d.openPorts.addAll(open);
        }
    }

    private void lookupName(NetDevice d) {
        try {
            String name = InetAddress.getByName(d.ip).getCanonicalHostName();
            if (name != null && !name.equals(d.ip)) d.hostname = name;
        } catch (Exception ignored) { }
    }

    // ---------- UPnP / SSDP ----------

    private void ssdpDiscover() {
        String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n";
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(500);
            byte[] out = msg.getBytes(StandardCharsets.US_ASCII);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (int i = 0; i < 2; i++) sock.send(new DatagramPacket(out, out.length, group, 1900));
            long end = System.currentTimeMillis() + 5000;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { sock.receive(p); } catch (SocketTimeoutException e) { continue; }
                String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                NetDevice d = device(p.getAddress().getHostAddress());
                for (String line : text.split("\r\n")) {
                    int c = line.indexOf(':');
                    if (c < 0) continue;
                    String key = line.substring(0, c).trim().toUpperCase(Locale.US);
                    String val = line.substring(c + 1).trim();
                    if (key.equals("SERVER") && d.ssdpServer.isEmpty()) d.ssdpServer = val;
                    if (key.equals("LOCATION") && d.ssdpLocations.size() < 3) d.ssdpLocations.add(val);
                }
            }
        } catch (IOException ignored) { }
    }

    private static final Pattern XML_FIELD = Pattern.compile(
            "<(friendlyName|manufacturer|modelName|modelDescription)>([^<]{1,80})</", Pattern.CASE_INSENSITIVE);

    private void fetchUpnpDescription(NetDevice d) {
        if (d.ssdpLocations.isEmpty() || !d.description.isEmpty()) return;
        String loc;
        synchronized (d.ssdpLocations) { loc = d.ssdpLocations.iterator().next(); }
        HttpURLConnection c = null;
        try {
            URL url = new URL(loc);
            if (!d.ip.equals(url.getHost())) return;          // only ask the device itself
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0 && bos.size() < 65536) bos.write(b, 0, n);
                Matcher m = XML_FIELD.matcher(bos.toString("UTF-8"));
                Set<String> parts = new LinkedHashSet<>();
                while (m.find() && parts.size() < 4) parts.add(m.group(2).trim());
                d.description = String.join(" · ", parts);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------- ONVIF (security camera standard) ----------

    private void onvifDiscover() {
        String probe = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" "
                + "xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" "
                + "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" "
                + "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">"
                + "<e:Header><w:MessageID>uuid:" + UUID.randomUUID() + "</w:MessageID>"
                + "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
                + "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>"
                + "</e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>";
        Pattern scope = Pattern.compile("onvif://www\\.onvif\\.org/(name|hardware)/([^\\s<]+)");
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(500);
            byte[] out = probe.getBytes(StandardCharsets.UTF_8);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (int i = 0; i < 2; i++) sock.send(new DatagramPacket(out, out.length, group, 3702));
            long end = System.currentTimeMillis() + 5000;
            byte[] buf = new byte[16384];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { sock.receive(p); } catch (SocketTimeoutException e) { continue; }
                String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                NetDevice d = device(p.getAddress().getHostAddress());
                d.onvif = true;
                Matcher m = scope.matcher(text);
                Set<String> parts = new LinkedHashSet<>();
                while (m.find() && parts.size() < 2) {
                    try { parts.add(URLDecoder.decode(m.group(2), "UTF-8")); } catch (Exception ignored) { }
                }
                if (!parts.isEmpty()) d.description = String.join(" · ", parts);
            }
        } catch (IOException ignored) { }
    }

    // ---------- Classification ----------

    private static void raise(NetDevice d, int level, String reason) {
        d.reasons.add(reason);
        if (level > d.level) d.level = level;
    }

    static void classify(NetDevice d) {
        if (d.isSelf) { d.kind = "This phone"; return; }
        if (d.isGateway) d.kind = "Wi-Fi router";

        String text = (d.hostname + " " + d.description + " " + d.ssdpServer).toLowerCase(Locale.US);

        if (d.onvif) raise(d, 2, "Answered a security-camera discovery request (ONVIF)");
        if (d.openPorts.contains(554) || d.openPorts.contains(8554)) raise(d, 2, "Video streaming port (RTSP) is open");
        if (d.openPorts.contains(37777)) raise(d, 2, "Port 37777 open, used by Dahua/Amcrest/Lorex cameras and recorders");
        if (d.openPorts.contains(34567)) raise(d, 2, "Port 34567 open, used by many cheap cameras and DVRs (XMEye)");
        Matcher cam = CAMERA_WORDS.matcher(text);
        if (cam.find()) raise(d, 2, "Its name or description mentions \"" + cam.group(1).trim() + "\"");

        if (d.openPorts.contains(8000)) raise(d, 1, "Port 8000 open (Hikvision cameras use it, but so do other gadgets)");
        if (d.openPorts.contains(6668)) raise(d, 1, "Tuya smart-home device (many cheap cameras run on Tuya)");
        if (d.openPorts.contains(1935)) raise(d, 1, "Live-streaming port (RTMP) is open");
        if (ESP_WORDS.matcher(text).find()) raise(d, 1, "ESP Wi-Fi chip, common in cheap and DIY cameras");

        if (d.kind.isEmpty()) {
            if (d.openPorts.contains(62078)) d.kind = "Apple device (iPhone/iPad)";
            else if (d.openPorts.contains(8009)) d.kind = "Google Cast device (TV or speaker)";
            else if (text.contains("roku")) d.kind = "Roku";
            else if (text.contains("sonos")) d.kind = "Sonos speaker";
            else if (text.contains("printer") || text.contains("epson") || text.contains("canon") || text.contains("brother")) d.kind = "Printer";
        }
        if (d.level == 0 && d.kind.isEmpty() && d.hostname.isEmpty() && d.description.isEmpty()) {
            d.reasons.add("Unidentified. Compare against the gadgets you can see in the home.");
        }
    }
}
