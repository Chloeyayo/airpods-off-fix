package com.maxine.airpodsofffix.shizuku;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.ParcelUuid;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.util.Arrays;

/**
 * Non-root AAP single-shot "restore Off" for AirPods on ColorOS.
 *
 * <p>Opens the classic L2CAP control channel (PSM 4097) ONCE via the hidden
 * {@code BluetoothSocket} constructor (type = TYPE_L2CAP = 3), runs the AAP
 * handshake, fires the unlock+Off sequence a single time, confirms via
 * telemetry, then closes. No polling, no HOLD, no reconnect loop -> none of the
 * channel churn that made the buds re-handshake and emit the extra reconnect
 * chime. We retry ONLY when the initial connect fails (device not yet routable),
 * never after a channel has been established.
 *
 * <p>Telemetry-validated recipe (memory "AirPods Off unlock recipe"): on
 * reconnect ColorOS disables Off on the buds (AAP 0x34 / 0x1A), so a bare
 * {@code 0x0D 01} is rejected. The fix is {@code 0x34 01} (allow Off)
 * [+ {@code 0x1A 07} (listening modes incl. Off) if the light tier did not land]
 * then {@code 0x0D 01} (set Off).
 *
 * <p>Logic ported from the proven probe {@code BtHold}, minus burst/HOLD/reconnect.
 */
public final class AapOff {

    /** Sink for progress lines; implementations marshal to the UI thread. */
    public interface Log { void line(String s); }

    static final String UUID_STR = "74ec2172-0bad-4d01-8f77-997b2be0722a";
    static final int PSM = 4097;
    static final int TYPE_L2CAP = 3;

    static final byte[] HS    = { 0x00,0x00,0x04,0x00,0x01,0x00,0x02,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00 };
    static final byte[] FEAT  = { 0x04,0x00,0x04,0x00,0x4D,0x00,(byte)0xD7,0x00,0x00,0x00,0x00,0x00,0x00,0x00 };
    static final byte[] NOTIF = { 0x04,0x00,0x04,0x00,0x0F,0x00,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF };
    static byte[] ctl(int id, int v) { return new byte[]{ 0x04,0x00,0x04,0x00,0x09,0x00,(byte)id,(byte)v,0x00,0x00,0x00 }; }
    static final byte[] ALLOW_OFF = ctl(0x34, 0x01);   // allow the Off mode at all
    static final byte[] MODES_OTA = ctl(0x1A, 0x07);   // listening-mode set incl. Off
    static final byte[] SET_OFF   = ctl(0x0D, 0x01);   // set current mode = Off

    static String modeName(int m) {
        switch (m) {
            case 1: return "OFF";
            case 2: return "ANC";
            case 3: return "TRANSPARENCY";
            case 4: return "ADAPTIVE";
            default: return "?(" + m + ")";
        }
    }

    private static long now() { return System.nanoTime() / 1_000_000L; }

    // ---- telemetry (current noise-control mode echoed by the buds) ----
    private final Object lock = new Object();
    private volatile int lastMode = -1;
    private volatile boolean channelDead = false;
    private byte[] carry = new byte[0];

    void scan(byte[] chunk, int n) {
        byte[] data;
        synchronized (lock) {
            if (carry.length == 0) {
                data = Arrays.copyOf(chunk, n);
            } else {
                data = new byte[carry.length + n];
                System.arraycopy(carry, 0, data, 0, carry.length);
                System.arraycopy(chunk, 0, data, carry.length, n);
            }
            carry = Arrays.copyOfRange(data, Math.max(0, data.length - 3), data.length);
        }
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == 0x09 && data[i + 1] == 0x00 && data[i + 2] == 0x0D) {
                synchronized (lock) { lastMode = data[i + 3] & 0xFF; }
            }
        }
    }

    private boolean waitMode(int target, int ms) throws InterruptedException {
        long end = now() + ms;
        while (now() < end && lastMode != target) Thread.sleep(20);
        return lastMode == target;
    }

    // ---- hidden classic-L2CAP socket (needs HiddenApi.exempt() to have run) ----
    private BluetoothSocket makeSocket(BluetoothDevice dev, ParcelUuid uuid, Log log) throws Exception {
        try {
            log.line("socket: public createL2capChannel(" + PSM + ")");
            return dev.createL2capChannel(PSM);
        } catch (Throwable th) {
            log.line("public L2CAP unavailable: " + th);
        }

        // 6-arg ctor is the primary; 7-arg (with a leading fd/securityFlags int) is the fallback.
        Object[][] specs = {
                { dev, TYPE_L2CAP, true, true, PSM, uuid },
                { dev, TYPE_L2CAP, 1, true, true, PSM, uuid },
        };
        Class<?>[][] sig = {
                { BluetoothDevice.class, int.class, boolean.class, boolean.class, int.class, ParcelUuid.class },
                { BluetoothDevice.class, int.class, int.class, boolean.class, boolean.class, int.class, ParcelUuid.class },
        };
        Exception last = null;
        for (int i = 0; i < specs.length; i++) {
            try {
                Constructor<?> c = BluetoothSocket.class.getDeclaredConstructor(sig[i]);
                c.setAccessible(true);
                log.line("socket: hidden BluetoothSocket ctor " + sig[i].length + "-arg");
                return (BluetoothSocket) c.newInstance(specs[i]);
            } catch (Throwable th) {
                last = (th instanceof Exception) ? (Exception) th : new RuntimeException(th);
            }
        }
        throw last;
    }

    // ---- send unlock+Off; return how it landed, or null if telemetry never showed OFF ----
    private String fixOff(OutputStream os) throws Exception {
        os.write(ALLOW_OFF); os.flush(); Thread.sleep(60);
        os.write(SET_OFF);   os.flush();
        if (waitMode(1, 700)) return "0x34";
        os.write(MODES_OTA); os.flush(); Thread.sleep(60);
        os.write(ALLOW_OFF); os.flush(); Thread.sleep(60);
        os.write(SET_OFF);   os.flush();
        return waitMode(1, 800) ? "0x34+0x1A" : null;
    }

    /**
     * Single-shot restore. Connects once (retrying only the connect itself, since
     * a just-reconnected bud may not be routable yet), fires the sequence once,
     * confirms, and closes.
     *
     * @return true if Off was confirmed, or fired blind on a telemetry-less
     *         ("zombie") connection; false if the channel could not be opened or
     *         Off was rejected.
     */
    public boolean restore(BluetoothAdapter adapter, BluetoothDevice dev, Log log) {
        ParcelUuid uuid = ParcelUuid.fromString(UUID_STR);

        BluetoothSocket sock = null;
        final int MAX_CONNECT_TRIES = 4;
        for (int attempt = 1; attempt <= MAX_CONNECT_TRIES && sock == null; attempt++) {
            BluetoothSocket s = null;
            try {
                s = makeSocket(dev, uuid, log);
                s.connect();
                sock = s;
            } catch (Throwable th) {
                if (s != null) { try { s.close(); } catch (Throwable ignored) {} }
                log.line("connect " + attempt + "/" + MAX_CONNECT_TRIES + " failed: " + th);
                if (attempt < MAX_CONNECT_TRIES) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { return false; }
                }
            }
        }
        if (sock == null) { log.line("could not open L2CAP channel (buds not reachable)"); return false; }

        try {
            final InputStream is = sock.getInputStream();
            Thread reader = new Thread(new AapReader(is, this), "aap-reader");
            reader.setDaemon(true);
            reader.start();

            OutputStream os = sock.getOutputStream();
            os.write(HS);    os.flush();
            os.write(FEAT);  os.flush();
            os.write(NOTIF); os.flush();

            // brief wait for the first telemetry frame (a zombie connection sends none)
            long end = now() + 900;
            while (now() < end && lastMode == -1) Thread.sleep(20);
            int mode = lastMode;

            if (mode == 1) { log.line("already OFF - nothing to send"); return true; }
            if (mode == -1) {
                log.line("no telemetry (zombie) - firing unlock+Off blind");
            } else {
                log.line("reconnect mode=" + modeName(mode) + " - sending unlock+Off");
            }

            String how = fixOff(os);
            if (how != null) { log.line("OFF confirmed via " + how); return true; }
            if (mode == -1) { log.line("unlock+Off sent blind (no telemetry to confirm)"); return true; }
            log.line("unlock+Off sent but OFF not confirmed");
            return false;
        } catch (Throwable th) {
            log.line("restore failed: " + th);
            return false;
        } finally {
            try { sock.close(); } catch (Throwable ignored) {}
        }
    }

    void markChannelDead() {
        channelDead = true;
    }
}
