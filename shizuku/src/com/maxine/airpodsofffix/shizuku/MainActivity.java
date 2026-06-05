package com.maxine.airpodsofffix.shizuku;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * User-facing Shizuku controller. The app owns UX and audio feedback; the shell daemon owns AAP.
 */
public class MainActivity extends Activity {
    private static final int REQ_BT = 100;
    private static final int REQ_SHIZUKU = 101;
    private static final int DEFAULT_CONNECT_DELAY_MS = 0;
    private static final int DEFAULT_GRACE_MS = 8000;
    private static final int DEFAULT_WAIT_DELAY_MS = 2500;
    private static final int WAIT_DELAY_MIN_MS = 500;
    private static final int WAIT_DELAY_STEP_MS = 100;
    private static final String REMOTE_DEX = "/data/local/tmp/airpods_off_bthold.dex";
    private static final String REMOTE_LOG = "/data/local/tmp/bthold_shizuku.log";
    private static final String REMOTE_COMMAND = "/data/local/tmp/bthold_command";
    private static final String KILL_DAEMON =
            "for pid in $(pidof app_process app_process32 app_process64 2>/dev/null); do "
                    + "c=$(tr '\\0' ' ' < /proc/$pid/cmdline 2>/dev/null); "
                    + "case \"$c\" in app_process*BtHold*) kill $pid 2>/dev/null;; esac; "
                    + "done; true";
    private static final String KILL_LOG_WATCHERS =
            "for pid in $(pidof tail 2>/dev/null); do "
                    + "c=$(tr '\\0' ' ' < /proc/$pid/cmdline 2>/dev/null); "
                    + "case \"$c\" in *bthold_shizuku.log*) kill $pid 2>/dev/null;; esac; "
                    + "done; true";

    private static final int MODE_OFF = 1;
    private static final int MODE_ANC = 2;
    private static final int MODE_TRANSPARENCY = 3;
    private static final int MODE_ADAPTIVE = 4;

    private static final int TEXT = Color.rgb(34, 39, 42);
    private static final int MUTED = Color.rgb(105, 113, 116);
    private static final int PRIMARY = Color.rgb(0, 108, 103);
    private static final int PRIMARY_DARK = Color.rgb(0, 72, 70);
    private static final int DANGER = Color.rgb(164, 54, 54);

    private final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
    private int selectedIndex = 0;
    private int connectDelayMs = DEFAULT_CONNECT_DELAY_MS;
    private int waitDelayMs = DEFAULT_WAIT_DELAY_MS;
    private int graceMs = DEFAULT_GRACE_MS;
    private String profileName = "立即保持";
    private boolean daemonKnownRunning = false;
    private int currentMode = -1;
    private int targetMode = -1;
    private int pendingMode = -1;
    private int pendingGeneration = 0;
    private int confirmedModeBeforePending = -1;
    private int logWatchGeneration = 0;
    private Process logWatchProcess;
    private boolean bluetoothReceiverRegistered = false;
    private boolean logAutoFollow = true;
    private boolean logUserTouching = false;

    private TextView deviceNameView;
    private TextView deviceMetaView;
    private TextView statusValueView;
    private TextView shizukuValueView;
    private TextView modeValueView;
    private TextView targetValueView;
    private TextView profileValueView;
    private View guidePanelView;
    private TextView guideTitleView;
    private TextView guideTextView;
    private EditText logView;
    private ScrollView mainScroll;
    private Button startButton;
    private Button stopButton;
    private Button authButton;
    private Button profileTakeoverButton;
    private Button profileBalancedButton;
    private TextView waitDelayValueView;
    private SeekBar waitDelaySeekBar;
    private Button[] modeButtons;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            new PermissionResultListener(this);
    private final BroadcastReceiver bluetoothReceiver = new BluetoothStateReceiver(this);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        updateShizukuViews();
        if (needsBtPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        } else {
            registerBluetoothReceiver();
            refreshDevices(false);
        }
        refreshDaemonStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusValueView != null) {
            if (!needsBtPermission()) registerBluetoothReceiver();
            refreshDevices(false);
            updateShizukuViews();
            refreshDaemonStatus();
        }
    }

    @Override
    protected void onDestroy() {
        stopLogWatch();
        unregisterBluetoothReceiver();
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            registerBluetoothReceiver();
            refreshDevices(true);
        } else if (requestCode == REQ_BT) {
            append("蓝牙权限被拒绝。");
        }
    }

    private void buildUi() {
        setContentView(R.layout.activity_main);
        mainScroll = (ScrollView) findViewById(R.id.main_scroll);
        modeValueView = (TextView) findViewById(R.id.mode_value);
        targetValueView = (TextView) findViewById(R.id.target_value);
        statusValueView = (TextView) findViewById(R.id.status_value);
        startButton = (Button) findViewById(R.id.start_button);
        stopButton = (Button) findViewById(R.id.stop_button);
        deviceNameView = (TextView) findViewById(R.id.device_name);
        deviceMetaView = (TextView) findViewById(R.id.device_meta);
        shizukuValueView = (TextView) findViewById(R.id.shizuku_value);
        profileValueView = (TextView) findViewById(R.id.profile_value);
        guidePanelView = findViewById(R.id.guide_panel);
        guideTitleView = (TextView) findViewById(R.id.guide_title);
        guideTextView = (TextView) findViewById(R.id.guide_text);
        logView = (EditText) findViewById(R.id.log_view);
        authButton = (Button) findViewById(R.id.auth_button);
        profileTakeoverButton = (Button) findViewById(R.id.profile_takeover);
        profileBalancedButton = (Button) findViewById(R.id.profile_balanced);
        waitDelayValueView = (TextView) findViewById(R.id.wait_delay_value);
        waitDelaySeekBar = (SeekBar) findViewById(R.id.wait_delay_seek);

        modeButtons = new Button[5];
        modeButtons[MODE_OFF] = (Button) findViewById(R.id.mode_off);
        modeButtons[MODE_ANC] = (Button) findViewById(R.id.mode_anc);
        modeButtons[MODE_TRANSPARENCY] = (Button) findViewById(R.id.mode_transparency);
        modeButtons[MODE_ADAPTIVE] = (Button) findViewById(R.id.mode_adaptive);

        startButton.setOnClickListener(new StartClickListener(this));
        stopButton.setOnClickListener(new StopClickListener(this));
        modeButtons[MODE_OFF].setOnClickListener(new ModeClickListener(this, MODE_OFF));
        modeButtons[MODE_ANC].setOnClickListener(new ModeClickListener(this, MODE_ANC));
        modeButtons[MODE_TRANSPARENCY].setOnClickListener(new ModeClickListener(this, MODE_TRANSPARENCY));
        modeButtons[MODE_ADAPTIVE].setOnClickListener(new ModeClickListener(this, MODE_ADAPTIVE));
        findViewById(R.id.refresh_button).setOnClickListener(new RefreshClickListener(this));
        findViewById(R.id.switch_button).setOnClickListener(new SwitchDeviceClickListener(this));
        authButton.setOnClickListener(new ShizukuAuthClickListener(this));
        profileTakeoverButton.setOnClickListener(new ProfileClickListener(this, "立即保持", DEFAULT_CONNECT_DELAY_MS, 8000));
        profileBalancedButton.setOnClickListener(new BalancedProfileClickListener(this));
        if (waitDelaySeekBar != null) {
            waitDelaySeekBar.setProgress(delayToSeekProgress(waitDelayMs));
            waitDelaySeekBar.setOnSeekBarChangeListener(new WaitDelayChangeListener(this));
        }
        findViewById(R.id.log_button).setOnClickListener(new LogClickListener(this));
        findViewById(R.id.copy_log_button).setOnClickListener(new CopyLogClickListener(this));
        findViewById(R.id.clear_log_button).setOnClickListener(new ClearLogClickListener(this));

        disableAllCaps(startButton, stopButton,
                modeButtons[MODE_OFF], modeButtons[MODE_ANC], modeButtons[MODE_TRANSPARENCY], modeButtons[MODE_ADAPTIVE],
                (Button) findViewById(R.id.refresh_button), (Button) findViewById(R.id.switch_button), authButton,
                profileTakeoverButton, profileBalancedButton,
                (Button) findViewById(R.id.log_button), (Button) findViewById(R.id.copy_log_button),
                (Button) findViewById(R.id.clear_log_button));

        logView.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        if (Build.VERSION.SDK_INT >= 21) {
            logView.setShowSoftInputOnFocus(false);
        }
        logView.setCursorVisible(false);
        logView.setSingleLine(false);
        logView.setMaxLines(Integer.MAX_VALUE);
        logView.setMinLines(1);
        logView.setTextIsSelectable(true);
        logView.setLongClickable(true);
        logView.setVerticalScrollBarEnabled(true);
        logView.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        logView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        logView.setHorizontallyScrolling(false);
        logView.setOnTouchListener(new LogTouchListener(this));
        View root = findViewById(R.id.root_content);
        if (root != null) root.requestFocus();
        if (mainScroll != null) mainScroll.post(new ScrollTopTask(this));
        updateProfileText();
        updateModeViews();
    }

    private void disableAllCaps(Button... buttons) {
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i] != null) buttons[i].setAllCaps(false);
        }
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

    private void registerBluetoothReceiver() {
        if (bluetoothReceiverRegistered || needsBtPermission()) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(bluetoothReceiver, filter);
        bluetoothReceiverRegistered = true;
    }

    private void unregisterBluetoothReceiver() {
        if (!bluetoothReceiverRegistered) return;
        try {
            unregisterReceiver(bluetoothReceiver);
        } catch (Throwable ignored) {
        }
        bluetoothReceiverRegistered = false;
    }

    private void onBluetoothEvent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        BluetoothDevice eventDevice = null;
        try {
            eventDevice = (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        } catch (Throwable ignored) {
        }
        if (eventDevice != null && !looksRelevant(safeName(eventDevice), eventDevice.getAddress())) return;
        if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            clearPendingMode();
        }
        refreshDevices(false);
        updateShizukuViews();
        refreshDaemonStatus();
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            append("耳机已连接。");
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            append("耳机已断开。");
        } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            append("蓝牙状态已变化。");
        }
    }

    private void refreshDevices() {
        refreshDevices(true);
    }

    private void refreshDevices(boolean logResult) {
        if (needsBtPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            return;
        }
        devices.clear();
        BluetoothAdapter bt = btAdapter();
        if (bt == null) {
            selectedIndex = 0;
            updateDeviceViews();
            if (logResult) append("未找到蓝牙适配器。");
            return;
        }
        for (BluetoothDevice device : bt.getBondedDevices()) {
            String name = safeName(device);
            if (looksRelevant(name, device.getAddress())) devices.add(device);
        }
        if (selectedIndex >= devices.size()) selectedIndex = 0;
        updateDeviceViews();
        if (logResult) append("已找到耳机：" + devices.size());
    }

    private void switchDevice() {
        if (devices.size() <= 1) {
            append(devices.isEmpty() ? "没有找到已配对的 AirPods。" : "只找到一副耳机。");
            return;
        }
        selectedIndex = (selectedIndex + 1) % devices.size();
        updateDeviceViews();
    }

    private void updateDeviceViews() {
        BluetoothDevice device = selectedDevice();
        if (device == null) {
            deviceNameView.setText("未选择耳机");
            deviceMetaView.setText("请先在系统蓝牙里配对 AirPods。");
            updateModeViews();
            return;
        }
        deviceNameView.setText(safeName(device));
        deviceMetaView.setText(device.getAddress() + "  " + (selectedIndex + 1) + "/" + devices.size()
                + (isDeviceConnected(device) ? "  已连接" : "  未连接"));
        updateModeViews();
    }

    private boolean looksRelevant(String name, String address) {
        String s = ((name == null ? "" : name) + " " + (address == null ? "" : address)).toLowerCase();
        return s.contains("airpods") || s.contains("air pods") || s.contains("beats");
    }

    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.length() == 0 ? "AirPods" : name;
        } catch (SecurityException e) {
            return "AirPods";
        }
    }

    private BluetoothDevice selectedDevice() {
        if (devices.isEmpty()) return null;
        if (selectedIndex < 0 || selectedIndex >= devices.size()) selectedIndex = 0;
        return devices.get(selectedIndex);
    }

    private boolean selectedDeviceConnected() {
        return isDeviceConnected(selectedDevice());
    }

    private boolean isDeviceConnected(BluetoothDevice device) {
        if (device == null) return false;
        try {
            Method m = BluetoothDevice.class.getDeclaredMethod("isConnected");
            m.setAccessible(true);
            Object result = m.invoke(device);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestShizuku() {
        if (!Shizuku.pingBinder()) {
            append("Shizuku 服务未运行。");
            updateShizukuViews();
            return;
        }
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                append("Shizuku 权限已授予。");
            } else {
                Shizuku.requestPermission(REQ_SHIZUKU);
            }
        } catch (Throwable t) {
            append("Shizuku 权限检查失败：" + t);
        }
        updateShizukuViews();
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
            if (!Shizuku.pingBinder()) return "服务未运行";
            return "uid=" + Shizuku.getUid() + " api=" + Shizuku.getVersion()
                    + " " + (hasShizukuPermission() ? "已授权" : "未授权");
        } catch (Throwable t) {
            return "错误：" + t;
        }
    }

    private void updateShizukuViews() {
        if (shizukuValueView != null) shizukuValueView.setText("Shizuku：" + shizukuState());
        updateAccessState();
    }

    private void updateAccessState() {
        boolean ready = hasShizukuPermission();
        if (guidePanelView != null) {
            guidePanelView.setVisibility(ready ? View.GONE : View.VISIBLE);
        }
        if (guideTitleView != null) {
            guideTitleView.setText("使用前需要 Shizuku 授权");
        }
        if (guideTextView != null) {
            guideTextView.setText("请先打开 Shizuku，确认服务正在运行，然后点「授权」。授权完成后再启动后台服务。");
        }
        if (authButton != null) {
            authButton.setText(ready ? "Shizuku 已授权" : "授权 Shizuku");
            authButton.setTextColor(ready ? TEXT : Color.WHITE);
            authButton.setBackgroundResource(ready ? R.drawable.button_normal : R.drawable.button_primary);
            authButton.setEnabled(!ready);
        }
        if (startButton != null) startButton.setEnabled(ready && selectedDevice() != null);
        if (stopButton != null) stopButton.setEnabled(ready);
        updateModeViews();
    }

    private void refreshDaemonStatus() {
        if (!hasShizukuPermission()) {
            setDaemonStatus(false, "未运行");
            return;
        }
        new Thread(new DaemonStatusTask(this), "airpods-status").start();
    }

    private void startHold() {
        BluetoothDevice device = selectedDevice();
        if (device == null) {
            append("未选择耳机。");
            return;
        }
        if (!hasShizukuPermission()) {
            append("需要先授予 Shizuku 权限。");
            requestShizuku();
            return;
        }
        String addr = device.getAddress();
        append("正在启动后台服务：" + safeName(device));
        setDaemonStatus(true, "启动中");
        new Thread(new StartTask(this, addr, connectDelayMs, graceMs, profileName), "airpods-start").start();
    }

    private void startHoldWorker(String addr, int delayMs, int graceMs, String profile) {
        try {
            File localDex = writeAssetDex();
            stopLogWatch();
            runShell(KILL_LOG_WATCHERS);
            runShell(KILL_DAEMON);
            runShell("cp '" + localDex.getAbsolutePath() + "' " + REMOTE_DEX
                    + " && chmod 644 " + REMOTE_DEX);
            String cmd = "rm -f " + REMOTE_LOG + "; "
                    + "CLASSPATH=" + REMOTE_DEX
                    + " nohup app_process /system/bin BtHold " + addr
                    + " 3600 " + graceMs + " " + delayMs + " 0"
                    + " " + REMOTE_COMMAND
                    + " > " + REMOTE_LOG + " 2>&1 &";
            runShell(cmd);
            startLogWatch();
            setDaemonStatusFromWorker(true, "运行中");
            logFromWorker("后台服务已启动：" + profile + "，延迟 " + delayMs + "ms。");
            Thread.sleep(900);
            refreshRemoteLog();
        } catch (Throwable t) {
            setDaemonStatusFromWorker(false, "未运行");
            clearPendingFromWorker();
            logFromWorker("启动失败：" + t);
        }
    }

    private void stopHold() {
        if (!hasShizukuPermission()) {
            append("需要先授予 Shizuku 权限。");
            requestShizuku();
            return;
        }
        stopLogWatch();
        new Thread(new StopTask(this), "airpods-stop").start();
    }

    private void requestMode(int mode) {
        if (!hasShizukuPermission()) {
            append("需要先授予 Shizuku 权限。");
            requestShizuku();
            return;
        }
        BluetoothDevice device = selectedDevice();
        if (device == null) {
            append("未选择耳机。");
            return;
        }
        if (!isDeviceConnected(device)) {
            append("AirPods 未连接。请先连接耳机，再切换模式。");
            updateDeviceViews();
            return;
        }
        setPendingMode(mode);
        append("正在切换到" + modeName(mode) + "...");
        new Thread(new ModeTask(this, mode, device.getAddress(), connectDelayMs, graceMs, profileName), "airpods-mode").start();
    }

    private void requestModeWorker(int mode, String addr, int delayMs, int graceMs, String profile) {
        try {
            runShell("printf '" + mode + "' > " + REMOTE_COMMAND + " && chmod 666 " + REMOTE_COMMAND);
            if (!daemonAliveWorker()) {
                logFromWorker("后台服务未运行，正在先启动。");
                startHoldWorker(addr, delayMs, graceMs, profile);
            } else {
                startLogWatch();
                setDaemonStatusFromWorker(true, "运行中");
                logFromWorker("模式命令已发送：" + modeName(mode));
            }
        } catch (Throwable t) {
            logFromWorker("模式命令失败：" + t);
            clearPendingFromWorker();
        }
    }

    private boolean daemonAliveWorker() throws Exception {
        String out = runShell("for pid in $(pidof app_process app_process32 app_process64 2>/dev/null); do "
                + "c=$(tr '\\0' ' ' < /proc/$pid/cmdline 2>/dev/null); "
                + "case \"$c\" in app_process*BtHold*) echo yes; exit 0;; esac; "
                + "done; echo no");
        return out.indexOf("yes") >= 0;
    }

    private void refreshRemoteLog() {
        if (!hasShizukuPermission()) {
            append("需要先授予 Shizuku 权限。");
            requestShizuku();
            return;
        }
        new Thread(new RemoteLogTask(this), "airpods-log").start();
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

    private void setProfile(String name, int delayMs, int graceMs) {
        profileName = name;
        connectDelayMs = delayMs;
        this.graceMs = graceMs;
        updateProfileText();
        append("连接策略：" + name);
        restartDaemonForProfileChange();
    }

    private void setWaitDelay(int delayMs, boolean fromUser) {
        waitDelayMs = clampWaitDelay(delayMs);
        profileName = "等待提示音";
        connectDelayMs = waitDelayMs;
        graceMs = DEFAULT_GRACE_MS;
        updateProfileText();
        if (fromUser) append("提示音等待：" + formatSeconds(waitDelayMs));
        if (fromUser) restartDaemonForProfileChange();
    }

    private void restartDaemonForProfileChange() {
        if (!daemonKnownRunning) return;
        if (!hasShizukuPermission() || selectedDevice() == null) return;
        append("策略已变更，正在重启后台服务使其生效。");
        startHold();
    }

    private int clampWaitDelay(int delayMs) {
        int max = WAIT_DELAY_MIN_MS + waitDelaySeekMax() * WAIT_DELAY_STEP_MS;
        if (delayMs < WAIT_DELAY_MIN_MS) return WAIT_DELAY_MIN_MS;
        if (delayMs > max) return max;
        return delayMs;
    }

    private int waitDelaySeekMax() {
        return waitDelaySeekBar == null ? 55 : waitDelaySeekBar.getMax();
    }

    private static int seekProgressToDelay(int progress) {
        return WAIT_DELAY_MIN_MS + progress * WAIT_DELAY_STEP_MS;
    }

    private static int delayToSeekProgress(int delayMs) {
        int progress = (delayMs - WAIT_DELAY_MIN_MS + WAIT_DELAY_STEP_MS / 2) / WAIT_DELAY_STEP_MS;
        if (progress < 0) return 0;
        if (progress > 55) return 55;
        return progress;
    }

    private static String formatSeconds(int delayMs) {
        return String.format(java.util.Locale.US, "%.1f秒", delayMs / 1000.0f);
    }

    private void updateProfileText() {
        boolean waitProfileSelected = "等待提示音".equals(profileName);
        if (profileValueView != null) {
            String detail = connectDelayMs <= 0
                    ? "连接后立刻保持当前模式，最快但可能盖住原生连接提示音。"
                    : "等待约" + formatSeconds(connectDelayMs) + "后再保持当前模式，优先保留原生连接提示音。";
            profileValueView.setText("连接策略：" + profileName + "。 " + detail);
        }
        if (waitDelayValueView != null) {
            waitDelayValueView.setText("提示音等待：" + formatSeconds(waitDelayMs));
            waitDelayValueView.setVisibility(waitProfileSelected ? View.VISIBLE : View.GONE);
        }
        if (waitDelaySeekBar != null) {
            int expected = delayToSeekProgress(waitDelayMs);
            if (waitDelaySeekBar.getProgress() != expected) waitDelaySeekBar.setProgress(expected);
            waitDelaySeekBar.setVisibility(waitProfileSelected ? View.VISIBLE : View.GONE);
            waitDelaySeekBar.setEnabled(waitProfileSelected);
        }
        if (profileTakeoverButton != null) {
            boolean selected = "立即保持".equals(profileName);
            profileTakeoverButton.setTextColor(selected ? Color.WHITE : TEXT);
            profileTakeoverButton.setBackgroundResource(selected ? R.drawable.button_primary : R.drawable.button_normal);
        }
        if (profileBalancedButton != null) {
            boolean selected = waitProfileSelected;
            profileBalancedButton.setTextColor(selected ? Color.WHITE : TEXT);
            profileBalancedButton.setBackgroundResource(selected ? R.drawable.button_primary : R.drawable.button_normal);
        }
    }

    private void startLogWatch() {
        final int generation;
        synchronized (this) {
            logWatchGeneration++;
            generation = logWatchGeneration;
            if (logWatchProcess != null) {
                logWatchProcess.destroy();
                logWatchProcess = null;
            }
        }
        new Thread(new LogWatchTask(this, generation), "airpods-log-watch").start();
    }

    private void stopLogWatch() {
        synchronized (this) {
            logWatchGeneration++;
            if (logWatchProcess != null) {
                logWatchProcess.destroy();
                logWatchProcess = null;
            }
        }
    }

    private boolean isLogWatchCurrent(int generation) {
        synchronized (this) {
            return generation == logWatchGeneration;
        }
    }

    private void setLogWatchProcess(Process process, int generation) {
        synchronized (this) {
            if (generation == logWatchGeneration) {
                logWatchProcess = process;
            } else {
                process.destroy();
            }
        }
    }

    private void clearLogWatchProcess(Process process) {
        synchronized (this) {
            if (logWatchProcess == process) logWatchProcess = null;
        }
    }

    private void watchDaemonLog(int generation) {
        Process process = null;
        try {
            String script = "while [ ! -f " + REMOTE_LOG + " ]; do sleep 0.2; done; "
                    + "tail -n +1 -f " + REMOTE_LOG;
            process = newShizukuProcess(new String[]{"/system/bin/sh", "-c", script}, null, null);
            setLogWatchProcess(process, generation);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while (isLogWatchCurrent(generation) && (line = reader.readLine()) != null) {
                handleDaemonLine(line);
            }
        } catch (Throwable t) {
            if (isLogWatchCurrent(generation)) logFromWorker("日志监听已停止：" + t);
        } finally {
            if (process != null) {
                process.destroy();
                clearLogWatchProcess(process);
            }
        }
    }

    private void handleDaemonLine(String line) {
        int mode = -1;
        int target = -1;
        if (line.indexOf("[ON]") >= 0 || line.indexOf("[REOPEN]") >= 0) {
            mode = parseModeAfter(line, "reconnect mode=");
            target = parseModeAfter(line, "target ");
        } else if (line.indexOf("[HOLD]") >= 0) {
            mode = parseModeAfter(line, "mode=");
            target = parseModeAfter(line, "target=");
        } else if (line.indexOf("[CMD]") >= 0) {
            if (line.indexOf("OK") >= 0) {
                target = parseModeAfter(line, "target ");
                mode = target;
            }
        } else if (line.indexOf("[USER]") >= 0) {
            mode = parseModeAfter(line, "manual ");
            target = mode;
        }
        if (validMode(pendingMode)) {
            if (line.indexOf("[CMD]") >= 0 && line.indexOf("OK") >= 0 && target == pendingMode) {
                setModesFromWorker(target, target);
            } else if (line.indexOf("[CMD]") >= 0 && line.indexOf("FAILED") >= 0) {
                clearPendingFromWorker();
            }
        } else if (validMode(mode) || validMode(target)) {
            setModesFromWorker(mode, target);
        }
        if (isImportantLine(line)) logFromWorker(line);
    }

    private boolean isImportantLine(String line) {
        return line.indexOf("[ON]") >= 0
                || line.indexOf("[REOPEN]") >= 0
                || line.indexOf("[CMD]") >= 0
                || line.indexOf("[FIX]") >= 0
                || line.indexOf("[USER]") >= 0
                || line.indexOf("[EOF]") >= 0
                || line.indexOf("BT: EXC") >= 0
                || line.indexOf("BT: no adapter") >= 0;
    }

    private int parseModeAfter(String line, String marker) {
        int pos = line.indexOf(marker);
        if (pos < 0) return -1;
        int start = pos + marker.length();
        int end = start;
        while (end < line.length()) {
            char c = line.charAt(end);
            if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') && c != '_') break;
            end++;
        }
        if (end <= start) return -1;
        return modeValue(line.substring(start, end));
    }

    private int modeValue(String value) {
        if (value == null) return -1;
        String s = value.trim();
        if ("1".equals(s) || "OFF".equalsIgnoreCase(s)) return MODE_OFF;
        if ("2".equals(s) || "ANC".equalsIgnoreCase(s) || "NOISE".equalsIgnoreCase(s)) return MODE_ANC;
        if ("3".equals(s) || "TRANSPARENCY".equalsIgnoreCase(s) || "TRANS".equalsIgnoreCase(s)) return MODE_TRANSPARENCY;
        if ("4".equals(s) || "ADAPTIVE".equalsIgnoreCase(s)) return MODE_ADAPTIVE;
        return -1;
    }

    private boolean validMode(int mode) {
        return mode >= MODE_OFF && mode <= MODE_ADAPTIVE;
    }

    private String modeName(int mode) {
        switch (mode) {
            case MODE_OFF: return "关闭";
            case MODE_ANC: return "降噪";
            case MODE_TRANSPARENCY: return "通透";
            case MODE_ADAPTIVE: return "自适应";
            default: return "未知";
        }
    }

    private void setCurrentMode(int mode) {
        if (validMode(mode)) currentMode = mode;
        updateModeViews();
    }

    private void setTargetMode(int mode) {
        if (validMode(mode)) targetMode = mode;
        updateModeViews();
    }

    private void setPendingMode(int mode) {
        if (validMode(mode)) {
            confirmedModeBeforePending = currentMode;
            pendingMode = mode;
            targetMode = mode;
            currentMode = mode;
            pendingGeneration++;
        } else {
            pendingMode = -1;
            confirmedModeBeforePending = -1;
            pendingGeneration++;
        }
        updateModeViews();
    }

    private void clearPendingMode() {
        if (validMode(confirmedModeBeforePending)) {
            currentMode = confirmedModeBeforePending;
            targetMode = confirmedModeBeforePending;
        }
        pendingMode = -1;
        confirmedModeBeforePending = -1;
        pendingGeneration++;
        updateModeViews();
    }

    private void setModes(int mode, int target) {
        if (validMode(mode)) currentMode = mode;
        if (validMode(target)) targetMode = target;
        if (validMode(pendingMode) && pendingMode == currentMode && pendingMode == targetMode) {
            pendingMode = -1;
            confirmedModeBeforePending = -1;
            pendingGeneration++;
        }
        updateModeViews();
    }

    private void updateModeViews() {
        if (modeValueView != null) modeValueView.setText(modeName(currentMode));
        if (targetValueView != null) {
            String label = validMode(pendingMode) ? "正在切换到" + modeName(pendingMode) : "目标：" + modeName(targetMode);
            targetValueView.setText(label);
        }
        if (modeButtons == null) return;
        boolean ready = hasShizukuPermission();
        boolean canControlDevice = ready && selectedDeviceConnected();
        for (int i = MODE_OFF; i <= MODE_ADAPTIVE; i++) {
            Button b = modeButtons[i];
            if (b == null) continue;
            if (i == pendingMode) {
                b.setTextColor(Color.WHITE);
                b.setText(modeName(i));
                b.setBackgroundResource(R.drawable.button_pending);
                b.setEnabled(false);
            } else if (i == targetMode) {
                b.setText(modeName(i));
                b.setTextColor(Color.WHITE);
                b.setBackgroundResource(R.drawable.button_primary);
                b.setEnabled(canControlDevice && !validMode(pendingMode));
            } else if (i == currentMode) {
                b.setText(modeName(i));
                b.setTextColor(TEXT);
                b.setBackgroundResource(R.drawable.button_current);
                b.setEnabled(canControlDevice && !validMode(pendingMode));
            } else {
                b.setText(modeName(i));
                b.setTextColor(TEXT);
                b.setBackgroundResource(R.drawable.button_normal);
                b.setEnabled(canControlDevice && !validMode(pendingMode));
            }
        }
    }

    private void setDaemonStatus(boolean running, String label) {
        daemonKnownRunning = running;
        boolean active = running && selectedDeviceConnected();
        String visibleLabel = running && !active ? "待连接" : label;
        if (statusValueView != null) {
            statusValueView.setText(visibleLabel);
            statusValueView.setBackgroundResource(active ? R.drawable.pill_running : R.drawable.pill_stopped);
        }
        if (startButton != null) startButton.setText(running ? "重启" : "启动");
        if (stopButton != null) {
            stopButton.setTextColor(running ? DANGER : MUTED);
        }
    }

    private void append(String msg) {
        if (logView == null) return;
        boolean shouldFollow = logAutoFollow && !logUserTouching && !hasActiveLogSelection();
        logView.append(msg + "\n");
        if (shouldFollow) logView.post(new ScrollLogBottomTask(this));
    }

    private void clearLog() {
        if (logView != null) {
            logAutoFollow = true;
            logView.setText("");
            logView.scrollTo(0, 0);
        }
    }

    private void copyLog() {
        if (logView == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            append("复制失败：剪贴板不可用。");
            return;
        }
        String text = logView.getText().toString();
        if (text.length() == 0) {
            append("没有可复制的日志。");
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("Pods 助手日志", text));
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
        append("日志已复制。");
    }

    private void logFromWorker(final String msg) {
        runOnUiThread(new UiLogTask(this, msg));
    }

    private void setDaemonStatusFromWorker(boolean running, String label) {
        runOnUiThread(new DaemonUiTask(this, running, label));
    }

    private void setModesFromWorker(int mode, int target) {
        runOnUiThread(new ModeUiTask(this, mode, target));
    }

    private void clearPendingFromWorker() {
        runOnUiThread(new ClearPendingTask(this));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean hasActiveLogSelection() {
        if (logView == null) return false;
        return logView.getSelectionStart() != logView.getSelectionEnd();
    }

    private int logScrollRange() {
        if (logView == null || logView.getLayout() == null) return 0;
        int contentHeight = logView.getLayout().getHeight()
                + logView.getPaddingTop()
                + logView.getPaddingBottom();
        return Math.max(0, contentHeight - logView.getHeight());
    }

    private boolean isLogAtBottom() {
        if (logView == null) return true;
        int range = logScrollRange();
        return range <= 0 || logView.getScrollY() >= range - dp(8);
    }

    private void updateLogAutoFollowAfterTouch() {
        logAutoFollow = isLogAtBottom() && !hasActiveLogSelection();
    }

    private void scrollLogToBottom() {
        if (logView == null) return;
        int bottom = logScrollRange();
        if (bottom > 0) logView.scrollTo(0, bottom);
    }

    private static final class ScrollTopTask implements Runnable {
        private final MainActivity activity;
        ScrollTopTask(MainActivity activity) { this.activity = activity; }
        @Override public void run() {
            if (activity.mainScroll != null) activity.mainScroll.scrollTo(0, 0);
        }
    }

    private static final class ScrollLogBottomTask implements Runnable {
        private final MainActivity activity;
        ScrollLogBottomTask(MainActivity activity) { this.activity = activity; }
        @Override public void run() {
            activity.scrollLogToBottom();
            activity.logAutoFollow = true;
        }
    }

    private static final class UpdateLogFollowTask implements Runnable {
        private final MainActivity activity;
        UpdateLogFollowTask(MainActivity activity) { this.activity = activity; }
        @Override public void run() {
            activity.updateLogAutoFollowAfterTouch();
        }
    }

    private static final class LogTouchListener implements View.OnTouchListener {
        private final MainActivity activity;
        LogTouchListener(MainActivity activity) { this.activity = activity; }
        @Override public boolean onTouch(View v, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                activity.logUserTouching = true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                activity.logUserTouching = false;
            }
            if (activity.mainScroll != null && activity.logScrollRange() > 0) {
                boolean touching = action == MotionEvent.ACTION_DOWN
                        || action == MotionEvent.ACTION_MOVE;
                activity.mainScroll.requestDisallowInterceptTouchEvent(touching);
            }
            if (activity.logView != null) {
                activity.logView.post(new UpdateLogFollowTask(activity));
            }
            return false;
        }
    }

    private static final class RefreshClickListener implements View.OnClickListener {
        private final MainActivity activity;
        RefreshClickListener(MainActivity activity) { this.activity = activity; }
        @Override public void onClick(View v) {
            activity.refreshDevices();
            activity.updateShizukuViews();
            activity.refreshDaemonStatus();
        }
    }

    private static final class SwitchDeviceClickListener implements View.OnClickListener {
        private final MainActivity activity;
        SwitchDeviceClickListener(MainActivity activity) { this.activity = activity; }
        @Override public void onClick(View v) { activity.switchDevice(); }
    }

    private static final class PermissionResultListener implements Shizuku.OnRequestPermissionResultListener {
        private final MainActivity activity;
        PermissionResultListener(MainActivity activity) { this.activity = activity; }
        @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (requestCode == REQ_SHIZUKU) {
                activity.append(grantResult == PackageManager.PERMISSION_GRANTED
                        ? "Shizuku 权限已授予。"
                        : "Shizuku 权限被拒绝。");
                activity.updateShizukuViews();
                activity.refreshDaemonStatus();
            }
        }
    }

    private static final class BluetoothStateReceiver extends BroadcastReceiver {
        private final MainActivity activity;
        BluetoothStateReceiver(MainActivity activity) { this.activity = activity; }
        @Override public void onReceive(Context context, Intent intent) {
            activity.onBluetoothEvent(intent);
        }
    }

    private static final class StartTask implements Runnable {
        private final MainActivity activity;
        private final String addr;
        private final int delayMs;
        private final int graceMs;
        private final String profile;
        StartTask(MainActivity activity, String addr, int delayMs, int graceMs, String profile) {
            this.activity = activity;
            this.addr = addr;
            this.delayMs = delayMs;
            this.graceMs = graceMs;
            this.profile = profile;
        }
        @Override public void run() { activity.startHoldWorker(addr, delayMs, graceMs, profile); }
    }

    private static final class ModeTask implements Runnable {
        private final MainActivity activity;
        private final int mode;
        private final String addr;
        private final int delayMs;
        private final int graceMs;
        private final String profile;
        ModeTask(MainActivity activity, int mode, String addr, int delayMs, int graceMs, String profile) {
            this.activity = activity;
            this.mode = mode;
            this.addr = addr;
            this.delayMs = delayMs;
            this.graceMs = graceMs;
            this.profile = profile;
        }
        @Override public void run() { activity.requestModeWorker(mode, addr, delayMs, graceMs, profile); }
    }

    private static final class StopTask implements Runnable {
        private final MainActivity activity;
        StopTask(MainActivity activity) { this.activity = activity; }
        @Override public void run() {
            try {
                activity.runShell(KILL_LOG_WATCHERS);
                activity.runShell(KILL_DAEMON);
                activity.setDaemonStatusFromWorker(false, "未运行");
                activity.logFromWorker("后台服务已停止。");
            } catch (Throwable t) {
                activity.logFromWorker("停止失败：" + t);
            }
        }
    }

    private static final class DaemonStatusTask implements Runnable {
        private final MainActivity activity;
        DaemonStatusTask(MainActivity activity) { this.activity = activity; }
        @Override public void run() {
            try {
                boolean alive = activity.daemonAliveWorker();
                activity.setDaemonStatusFromWorker(alive, alive ? "运行中" : "未运行");
                if (alive) activity.startLogWatch();
            } catch (Throwable t) {
                activity.setDaemonStatusFromWorker(false, "未运行");
            }
        }
    }

    private static final class RemoteLogTask implements Runnable {
        private final MainActivity activity;
        RemoteLogTask(MainActivity activity) { this.activity = activity; }
        @Override public void run() {
            try {
                String out = activity.runShell("cat " + REMOTE_LOG + " 2>/dev/null || echo '（暂无日志）'");
                activity.logFromWorker("----- 守护进程日志 -----\n" + activity.tail(out, 5000));
            } catch (Throwable t) {
                activity.logFromWorker("读取日志失败：" + t);
            }
        }
    }

    private static final class LogWatchTask implements Runnable {
        private final MainActivity activity;
        private final int generation;
        LogWatchTask(MainActivity activity, int generation) {
            this.activity = activity;
            this.generation = generation;
        }
        @Override public void run() { activity.watchDaemonLog(generation); }
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

    private static final class DaemonUiTask implements Runnable {
        private final MainActivity activity;
        private final boolean running;
        private final String label;
        DaemonUiTask(MainActivity activity, boolean running, String label) {
            this.activity = activity;
            this.running = running;
            this.label = label;
        }
        @Override public void run() { activity.setDaemonStatus(running, label); }
    }

    private static final class ClearPendingTask implements Runnable {
        private final MainActivity activity;
        ClearPendingTask(MainActivity activity) { this.activity = activity; }
        @Override public void run() { activity.clearPendingMode(); }
    }

    private static final class ModeUiTask implements Runnable {
        private final MainActivity activity;
        private final int mode;
        private final int target;
        ModeUiTask(MainActivity activity, int mode, int target) {
            this.activity = activity;
            this.mode = mode;
            this.target = target;
        }
        @Override public void run() { activity.setModes(mode, target); }
    }

    private static final class ShizukuAuthClickListener implements View.OnClickListener {
        private final MainActivity activity;
        ShizukuAuthClickListener(MainActivity activity) { this.activity = activity; }
        @Override public void onClick(View v) { activity.requestShizuku(); }
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

    private static final class ModeClickListener implements View.OnClickListener {
        private final MainActivity activity;
        private final int mode;
        ModeClickListener(MainActivity activity, int mode) {
            this.activity = activity;
            this.mode = mode;
        }
        @Override public void onClick(View v) { activity.requestMode(mode); }
    }

    private static final class ProfileClickListener implements View.OnClickListener {
        private final MainActivity activity;
        private final String name;
        private final int delayMs;
        private final int graceMs;
        ProfileClickListener(MainActivity activity, String name, int delayMs, int graceMs) {
            this.activity = activity;
            this.name = name;
            this.delayMs = delayMs;
            this.graceMs = graceMs;
        }
        @Override public void onClick(View v) { activity.setProfile(name, delayMs, graceMs); }
    }

    private static final class BalancedProfileClickListener implements View.OnClickListener {
        private final MainActivity activity;
        BalancedProfileClickListener(MainActivity activity) { this.activity = activity; }
        @Override public void onClick(View v) {
            activity.setProfile("等待提示音", activity.waitDelayMs, DEFAULT_GRACE_MS);
        }
    }

    private static final class WaitDelayChangeListener implements SeekBar.OnSeekBarChangeListener {
        private final MainActivity activity;
        WaitDelayChangeListener(MainActivity activity) { this.activity = activity; }
        @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (fromUser) activity.setWaitDelay(seekProgressToDelay(progress), false);
        }
        @Override public void onStartTrackingTouch(SeekBar seekBar) { }
        @Override public void onStopTrackingTouch(SeekBar seekBar) {
            activity.setWaitDelay(seekProgressToDelay(seekBar.getProgress()), true);
        }
    }

    private static final class LogClickListener implements View.OnClickListener {
        private final MainActivity activity;
        LogClickListener(MainActivity activity) { this.activity = activity; }
        @Override public void onClick(View v) { activity.refreshRemoteLog(); }
    }

    private static final class CopyLogClickListener implements View.OnClickListener {
        private final MainActivity activity;
        CopyLogClickListener(MainActivity activity) { this.activity = activity; }
        @Override public void onClick(View v) { activity.copyLog(); }
    }

    private static final class ClearLogClickListener implements View.OnClickListener {
        private final MainActivity activity;
        ClearLogClickListener(MainActivity activity) { this.activity = activity; }
        @Override public void onClick(View v) { activity.clearLog(); }
    }
}
