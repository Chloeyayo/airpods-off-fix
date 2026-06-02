package com.maxine.airpodsofffix.nonroot;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
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

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_BT = 100;
    private static final int AIRPODS_PSM = 4097;
    private static final byte[] ENABLE_OFF_MODE = {
            0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x34, 0x01, 0x00, 0x00, 0x00
    };
    private static final byte[] SET_NOISE_MODE_OFF = {
            0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x0d, 0x01, 0x00, 0x00, 0x00
    };

    private final List<BluetoothDevice> devices = new ArrayList<BluetoothDevice>();
    private ArrayAdapter<String> adapter;
    private ListView listView;
    private TextView logView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        if (needsBtPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        } else {
            refreshDevices();
        }
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
        title.setText("AirPods Off Fix Non-root");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = new TextView(this);
        hint.setText("Experimental: connects to AirPods L2CAP PSM 4097 and sends restore-Off commands.");
        hint.setPadding(0, 0, 0, dp(12));
        root.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        listView = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, new ArrayList<String>());
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(new RefreshClickListener(this));
        buttons.addView(refresh, new LinearLayout.LayoutParams(0, -2, 1));

        Button restore = new Button(this);
        restore.setText("Restore Off");
        restore.setOnClickListener(new RestoreClickListener(this));
        buttons.addView(restore, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setPadding(0, dp(8), 0, 0);
        root.addView(logView, new LinearLayout.LayoutParams(-1, dp(140)));

        setContentView(root);
    }

    private boolean needsBtPermission() {
        return Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED;
    }

    private void refreshDevices() {
        if (needsBtPermission()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
            return;
        }
        devices.clear();
        adapter.clear();
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
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
        if (!devices.isEmpty()) {
            listView.setItemChecked(0, true);
        }
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

    private void restoreSelected() {
        if (devices.isEmpty()) {
            append("No candidate selected.");
            return;
        }
        int pos = listView.getCheckedItemPosition();
        if (pos < 0 || pos >= devices.size()) {
            pos = 0;
        }
        final BluetoothDevice device = devices.get(pos);
        append("Restoring Off for " + safeName(device) + "...");
        new Thread(new RestoreTask(this, device), "airpods-off-nonroot").start();
    }

    private void runRestore(BluetoothDevice device) {
        BluetoothSocket socket = null;
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null) {
                bt.cancelDiscovery();
            }
            socket = createSocket(device);
            logFromWorker("Connecting L2CAP PSM " + AIRPODS_PSM + "...");
            socket.connect();
            OutputStream out = socket.getOutputStream();
            for (int i = 0; i < 2; i++) {
                write(out, ENABLE_OFF_MODE);
                sleep(120);
                write(out, SET_NOISE_MODE_OFF);
                sleep(180);
            }
            out.flush();
            logFromWorker("Restore commands sent.");
        } catch (Throwable t) {
            logFromWorker("Failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private BluetoothSocket createSocket(BluetoothDevice device) throws Exception {
        try {
            return device.createInsecureL2capChannel(AIRPODS_PSM);
        } catch (Throwable first) {
            try {
                return device.createL2capChannel(AIRPODS_PSM);
            } catch (Throwable second) {
                Method m = device.getClass().getMethod("createL2capSocket", int.class);
                return (BluetoothSocket) m.invoke(device, Integer.valueOf(AIRPODS_PSM));
            }
        }
    }

    private void write(OutputStream out, byte[] data) throws Exception {
        out.write(data);
        out.flush();
        logFromWorker("Sent " + hex(data));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void append(String msg) {
        logView.append(msg + "\n");
        int scroll = logView.getLayout() == null ? 0 : logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight();
        if (scroll > 0) {
            logView.scrollTo(0, scroll);
        }
    }

    private void logFromWorker(final String msg) {
        runOnUiThread(new LogTask(this, msg));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            int v = data[i] & 0xff;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private static final class RefreshClickListener implements View.OnClickListener {
        private final MainActivity activity;

        RefreshClickListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onClick(View v) {
            activity.refreshDevices();
        }
    }

    private static final class RestoreClickListener implements View.OnClickListener {
        private final MainActivity activity;

        RestoreClickListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onClick(View v) {
            activity.restoreSelected();
        }
    }

    private static final class RestoreTask implements Runnable {
        private final MainActivity activity;
        private final BluetoothDevice device;

        RestoreTask(MainActivity activity, BluetoothDevice device) {
            this.activity = activity;
            this.device = device;
        }

        @Override
        public void run() {
            activity.runRestore(device);
        }
    }

    private static final class LogTask implements Runnable {
        private final MainActivity activity;
        private final String msg;

        LogTask(MainActivity activity, String msg) {
            this.activity = activity;
            this.msg = msg;
        }

        @Override
        public void run() {
            activity.append(msg);
        }
    }
}
