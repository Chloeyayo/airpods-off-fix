package com.maxine.airpodsofffix.shizuku;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * Shizuku build: the app is only the control panel. The actual AAP socket is opened by a
 * shell-uid app_process daemon, because that is the path already verified on this ColorOS build.
 */
public class MainActivity extends Activity {
    private static final int REQ_BT = 100;
    private static final int REQ_SHIZUKU = 101;
    private static final int DEFAULT_CONNECT_DELAY_MS = 2500;
    private static final String REMOTE_DEX = "/data/local/tmp/airpods_off_bthold.dex";
    private static final String REMOTE_LOG = "/data/local/tmp/bthold_shizuku.log";
    private static final String KILL_DAEMON =
            "for p in /proc/[0-9]*; do "
                    + "c=$(tr '\\0' ' ' < $p/cmdline 2>/dev/null); "
                    + "case \"$c\" in app_process*BtHold*) kill ${p##*/} 2>/dev/null;; esac; "
                    + "done; true";

    private final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
    private ArrayAdapter<String> adapter;
    private ListView listView;
    private TextView logView;
    private int connectDelayMs = DEFAULT_CONNECT_DELAY_MS;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            new PermissionResultListener(this);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        append("Shizuku: " + shizukuState());
        if (needsBtPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        } else {
            refreshDevices();
        }
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            refreshDevices();
        } else {
            append("Bluetooth permission denied.");
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("AirPods Off Fix (Shizuku)");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("Uses Shizuku to launch the proven shell daemon: single-shot Off, then passive hold.");
        hint.setPadding(0, 0, 0, dp(12));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        listView = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, new ArrayList<String>());
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = button("Refresh", new RefreshClickListener(this));
        Button auth = button("Shizuku Auth", new ShizukuAuthClickListener(this));
        row1.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));
        row1.addView(auth, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row1, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        Button start = button("Start Hold", new StartClickListener(this));
        Button stop = button("Stop", new StopClickListener(this));
        Button log = button("Log", new LogClickListener(this));
        row2.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
        row2.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
        row2.addView(log, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row2, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.addView(button("500ms", new DelayClickListener(this, 500)), new LinearLayout.LayoutParams(0, -2, 1));
        row3.addView(button("1000ms", new DelayClickListener(this, 1000)), new LinearLayout.LayoutParams(0, -2, 1));
        row3.addView(button("1500ms", new DelayClickListener(this, 1500)), new LinearLayout.LayoutParams(0, -2, 1));
        row3.addView(button("2500ms", new DelayClickListener(this, 2500)), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row3, new LinearLayout.LayoutParams(-1, -2));

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setPadding(0, dp(8), 0, 0);
        root.addView(logView, new LinearLayout.LayoutParams(-1, dp(210)));

        setContentView(root);
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(listener);
        return b;
    }

    private boolean needsBtPermission() {
        return Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED;
    }

    private BluetoothAdapter btAdapter() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null && bm.getAdapter() != null) return bm.getAdapter();
        return BluetoothAdapter.getDefaultAdapter();
    }

    private void refreshDevices() {
        if (needsBtPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            return;
        }
        devices.clear();
        adapter.clear();
        BluetoothAdapter bt = btAdapter();
        if (bt == null) {
            append("No Bluetooth adapter.");
            return;
        }
        for (BluetoothDevice device : bt.getBondedDevices()) {
            String name = safeName(device);
            if (looksRelevant(name, device.getAddress())) {
                devices.add(device);
                adapter.add(name + "  " + device.getAddress());
            }
        }
        adapter.notifyDataSetChanged();
        if (!devices.isEmpty()) listView.setItemChecked(0, true);
        append("Found paired candidates: " + devices.size());
    }

    private boolean looksRelevant(String name, String address) {
        String s = ((name == null ? "" : name) + " " + (address == null ? "" : address)).toLowerCase();
        return s.contains("airpods") || s.contains("air pods") || s.contains("beats");
    }

    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.length() == 0 ? "Unknown" : name;
        } catch (SecurityException e) {
            return "Unknown";
        }
    }

    private BluetoothDevice selectedDevice() {
        if (devices.isEmpty()) return null;
        int pos = listView.getCheckedItemPosition();
        if (pos < 0 || pos >= devices.size()) pos = 0;
        return devices.get(pos);
    }

    private void requestShizuku() {
        if (!Shizuku.pingBinder()) {
            append("Shizuku service is not running.");
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                append("Shizuku permission already granted.");
            } else {
                Shizuku.requestPermission(REQ_SHIZUKU);
            }
        } catch (Throwable t) {
            append("Shizuku permission check failed: " + t);
        }
    }

    private boolean hasShizukuPermission() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private String shizukuState() {
        try {
            if (!Shizuku.pingBinder()) return "service not running";
            return "uid=" + Shizuku.getUid() + " api=" + Shizuku.getVersion()
                    + " permission=" + (hasShizukuPermission() ? "granted" : "not granted");
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    private void startHold() {
        BluetoothDevice device = selectedDevice();
        if (device == null) {
            append("No AirPods selected.");
            return;
        }
        if (!hasShizukuPermission()) {
            append("Need Shizuku permission first.");
            requestShizuku();
            return;
        }
        final String addr = device.getAddress();
        final int delay = connectDelayMs;
        append("Starting shell daemon for " + safeName(device) + " " + addr + " delay=" + delay + "ms");
        new Thread(new StartTask(this, addr, delay), "airpods-shizuku-start").start();
    }

    private void startHoldWorker(String addr, int delayMs) {
        try {
            File localDex = writeAssetDex();
            runShell(KILL_DAEMON);
            runShell("cp '" + localDex.getAbsolutePath() + "' " + REMOTE_DEX
                    + " && chmod 644 " + REMOTE_DEX);
            String cmd = "rm -f " + REMOTE_LOG + "; "
                    + "CLASSPATH=" + REMOTE_DEX
                    + " nohup app_process /system/bin BtHold " + addr
                    + " 3600 8000 " + delayMs + " > " + REMOTE_LOG + " 2>&1 &";
            runShell(cmd);
            logFromWorker("Daemon started. Connect delay: " + delayMs + "ms. Log: " + REMOTE_LOG);
            Thread.sleep(2500);
            refreshRemoteLog();
        } catch (Throwable t) {
            logFromWorker("Start failed: " + t);
        }
    }

    private void stopHold() {
        if (!hasShizukuPermission()) {
            append("Need Shizuku permission first.");
            requestShizuku();
            return;
        }
        new Thread(new StopTask(this), "airpods-shizuku-stop").start();
    }

    private void refreshRemoteLog() {
        if (!hasShizukuPermission()) {
            append("Need Shizuku permission first.");
            requestShizuku();
            return;
        }
        new Thread(new RemoteLogTask(this), "airpods-shizuku-log").start();
    }

    private File writeAssetDex() throws Exception {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        File out = new File(dir, "bthold.dex");
        InputStream in = getAssets().open("bthold.dex");
        try {
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n > 0) os.write(buf, 0, n);
                }
            } finally {
                os.close();
            }
        } finally {
            in.close();
        }
        return out;
    }

    private String runShell(String script) throws Exception {
        Process p = newShizukuProcess(new String[]{"/system/bin/sh", "-c", script}, null, null);
        String out = readAll(p.getInputStream());
        String err = readAll(p.getErrorStream());
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("exit " + code + "\n" + out + err);
        return out + err;
    }

    private Process newShizukuProcess(String[] cmd, String[] env, String dir) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, new Object[]{cmd, env, dir});
    }

    private String readAll(InputStream is) throws Exception {
        byte[] buf = new byte[4096];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = is.read(buf)) >= 0) {
            if (n > 0) sb.append(new String(buf, 0, n));
        }
        return sb.toString();
    }

    private String tail(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(s.length() - max);
    }

    private void append(String msg) {
        logView.append(msg + "\n");
        int scroll = logView.getLayout() == null ? 0 : logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight();
        if (scroll > 0) logView.scrollTo(0, scroll);
    }

    private void logFromWorker(final String msg) {
        runOnUiThread(new UiLogTask(this, msg));
    }

    private void setConnectDelay(int delayMs) {
        connectDelayMs = delayMs;
        append("Connect delay set to " + delayMs + "ms.");
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class RefreshClickListener implements View.OnClickListener {
        private final MainActivity activity;
        RefreshClickListener(MainActivity activity) { this.activity = activity; }
        @Override public void onClick(View v) { activity.refreshDevices(); }
    }

    private static final class PermissionResultListener implements Shizuku.OnRequestPermissionResultListener {
        private final MainActivity activity;
        PermissionResultListener(MainActivity activity) { this.activity = activity; }
        @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (requestCode == REQ_SHIZUKU) {
                activity.append(grantResult == PackageManager.PERMISSION_GRANTED
                        ? "Shizuku permission granted."
                        : "Shizuku permission denied.");
            }
        }
    }

    private static final class StartTask implements Runnable {
        private final MainActivity activity;
        private final String addr;
        private final int delayMs;
        StartTask(MainActivity activity, String addr, int delayMs) {
            this.activity = activity;
            this.addr = addr;
            this.delayMs = delayMs;
        }
        @Override public void run() { activity.startHoldWorker(addr, delayMs); }
    }

    private static final class StopTask implements Runnable {
        private final MainActivity activity;
        StopTask(MainActivity activity) { this.activity = activity; }
        @Override public void run() {
            try {
                activity.runShell(KILL_DAEMON);
                activity.logFromWorker("Daemon stopped.");
            } catch (Throwable t) {
                activity.logFromWorker("Stop failed: " + t);
            }
        }
    }

    private static final class RemoteLogTask implements Runnable {
        private final MainActivity activity;
        RemoteLogTask(MainActivity activity) { this.activity = activity; }
        @Override public void run() {
            try {
                String out = activity.runShell("cat " + REMOTE_LOG + " 2>/dev/null || echo '(no log yet)'");
                activity.logFromWorker("----- daemon log -----\n" + activity.tail(out, 4000));
            } catch (Throwable t) {
                activity.logFromWorker("Log failed: " + t);
            }
        }
    }

    private static final class UiLogTask implements Runnable {
        private final MainActivity activity;
        private final String msg;
        UiLogTask(MainActivity activity, String msg) {
            this.activity = activity;
            this.msg = msg;
        }
        @Override public void run() { activity.append(msg); }
    }

    private static final class ShizukuAuthClickListener implements View.OnClickListener {
        private final MainActivity activity;
        ShizukuAuthClickListener(MainActivity activity) { this.activity = activity; }
        @Override public void onClick(View v) {
            activity.append("Shizuku: " + activity.shizukuState());
            activity.requestShizuku();
        }
    }

    private static final class StartClickListener implements View.OnClickListener {
        private final MainActivity activity;
        StartClickListener(MainActivity activity) { this.activity = activity; }
        @Override public void onClick(View v) { activity.startHold(); }
    }

    private static final class StopClickListener implements View.OnClickListener {
        private final MainActivity activity;
        StopClickListener(MainActivity activity) { this.activity = activity; }
        @Override public void onClick(View v) { activity.stopHold(); }
    }

    private static final class LogClickListener implements View.OnClickListener {
        private final MainActivity activity;
        LogClickListener(MainActivity activity) { this.activity = activity; }
        @Override public void onClick(View v) { activity.refreshRemoteLog(); }
    }

    private static final class DelayClickListener implements View.OnClickListener {
        private final MainActivity activity;
        private final int delayMs;
        DelayClickListener(MainActivity activity, int delayMs) {
            this.activity = activity;
            this.delayMs = delayMs;
        }
        @Override public void onClick(View v) { activity.setConnectDelay(delayMs); }
    }
}
