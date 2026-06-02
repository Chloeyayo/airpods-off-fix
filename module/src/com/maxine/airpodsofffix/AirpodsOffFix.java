package com.maxine.airpodsofffix;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * ColorOS / AirPods fixes. Targets ColorOS 16 (oplusrom V16.1.0) MyDevices 17.1.5,
 * still compatible with older ColorOS via name fallbacks.
 *
 *  1. Keep "Off" noise-control available: block the AAP
 *     DISABLE_OFF_LISTENING_MODE frame (04 00 04 00 09 00 34 02 00 00 00) that
 *     ColorOS sends on connect, which otherwise forces Off -> Transparency.
 *     Primary path: wrap BluetoothSocket.getOutputStream() and drop that exact
 *     11-byte frame. This is byte-pattern based and survives MyDevices updates.
 *
 *  2. Show "Off" in MyDevices: the AirPods settings page builds its noise-control
 *     selector from a feature mask. Bit 0x200 = "noise off supported". On AirPods
 *     Pro that bit is cleared, so "Off" never appears. Hook the feature-support
 *     method and OR 0x200 back in so the selector rebuilds WITH the Off option.
 *       - v17.1.5: com.oplus.mydevices.bluetooth.airpods.AirpodsSettingActivity.m2(int)
 *         -> AirpodsBluetoothManager$a.H(mask) parses (mask & 0x200) into r0
 *         (supportsNoiseOff); m2() then rebuilds the selector (o2()/z1()).
 *       - legacy:  com.heytap.mydevices.core.ui.apple.AirpodsSettingActivity.q2(int)
 *         plus forcing the Off row View (e1()) visible.
 *
 *  3. Stop the bogus page grey-out: AirPods release the idle HFP link a few
 *     seconds after connect; the MyDevices broadcast receiver maps that single
 *     profile drop (A2DP/HFP CONNECTION_STATE_CHANGED, extra.STATE==0) onto the
 *     whole device by calling the state dispatcher with STATE_DISCONNECTED.
 *     Hook the dispatcher and drop STATE_DISCONNECTED while the device is really
 *     still connected (ACL up, isConnected()==true). A user-initiated disconnect
 *     opens a short window in which STATE_DISCONNECTED is honored so the page
 *     greys out.
 *       - v17.1.5: AirpodsBluetoothManager.stateUpdate(String,BluetoothDevice)
 *                  + disconnectAirpods() + isConnected()
 *       - legacy:  AirpodsBluetoothManager.I(String,BluetoothDevice) + o()
 *                  + reflective isConnected() on field d/device.
 *
 * Hook callbacks are NAMED static nested classes (not anonymous) to avoid a
 * d8/R8 internal crash on method-local anonymous classes.
 */
public class AirpodsOffFix implements IXposedHookLoadPackage {

    private static final String TAG = "AirpodsOffFix";
    private static final String CLS = "com.oplus.bluetooth.feature.airpods.AirPodsManager";
    private static final String MYDEVICES_PKG = "com.heytap.mydevices";

    // AirPods settings page: moved package in MyDevices 17.x. Try new then legacy.
    private static final String MYDEVICES_AIRPODS_SETTINGS_V17 =
            "com.oplus.mydevices.bluetooth.airpods.AirpodsSettingActivity";
    private static final String MYDEVICES_AIRPODS_SETTINGS_LEGACY =
            "com.heytap.mydevices.core.ui.apple.AirpodsSettingActivity";
    // Connection manager: same FQN across versions, only method names changed.
    private static final String MYDEVICES_AIRPODS_BT_MANAGER =
            "com.heytap.mydevices.core.bluetooth.AirpodsBluetoothManager";

    private static final int FEATURE_NOISE_OFF = 0x200;
    // After the user taps "Disconnect" in MyDevices we must let STATE_DISCONNECTED
    // through so the page greys out, even though isConnected() can briefly still
    // report true while the A2DP/HFP/ACL links tear down.
    private static final long MYDEVICES_USER_DISCONNECT_WINDOW_MS = 8000L;
    private static volatile boolean sHooked = false;
    private static volatile long sMyDevicesUserDisconnectAtMs = 0L;
    private static final List<XC_MethodHook.Unhook> sWatchers = new ArrayList<XC_MethodHook.Unhook>();
    private static final WriteHook WRITE_HOOK = new WriteHook();

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (MYDEVICES_PKG.equals(lpparam.packageName)) {
            hookMyDevicesAirPodsSettings(lpparam.classLoader);
            return;
        }

        if (!"com.android.bluetooth".equals(lpparam.packageName)
                && !"com.heytap.accessory".equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + ": loaded into " + lpparam.packageName + ", arming hooks");

        hookBluetoothSocketOutputStream();

        if (!"com.android.bluetooth".equals(lpparam.packageName)) return;

        if (tryKnownLoaders(lpparam.classLoader)) return; // best effort

        try {
            Method loadClass1 = ClassLoader.class.getDeclaredMethod("loadClass", String.class);
            addWatcher(XposedBridge.hookMethod(loadClass1, new ClassResultWatcher("ClassLoader.loadClass(String)")));

            Method loadClass2 = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
            addWatcher(XposedBridge.hookMethod(loadClass2, new ClassResultWatcher("ClassLoader.loadClass(String,boolean)")));

            Method findClass = Class.forName("dalvik.system.BaseDexClassLoader")
                    .getDeclaredMethod("findClass", String.class);
            addWatcher(XposedBridge.hookMethod(findClass, new ClassResultWatcher("BaseDexClassLoader.findClass")));

            XposedBridge.log(TAG + ": class watchers installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ERROR arming watcher: " + t);
        }
    }

    // ----------------------------------------------------------------------
    // MyDevices hooks (functions #2 and #3). Each hook is installed
    // independently so a single renamed symbol can never disable the others.
    // ----------------------------------------------------------------------

    private static void hookMyDevicesAirPodsSettings(ClassLoader cl) {
        hookMyDevicesConnectionState(cl);
        hookMyDevicesOffOption(cl);
    }

    /** Function #3: suppress bogus STATE_DISCONNECTED; honor user disconnect. */
    private static void hookMyDevicesConnectionState(ClassLoader cl) {
        Class<?> btManager;
        try {
            btManager = cl.loadClass(MYDEVICES_AIRPODS_BT_MANAGER);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ERROR loading AirpodsBluetoothManager: " + t);
            return;
        }
        // State dispatcher: v17 stateUpdate(String,BluetoothDevice), legacy I(...).
        try {
            Method state = findMethod(btManager, new String[]{"stateUpdate", "I"},
                    String.class, android.bluetooth.BluetoothDevice.class);
            if (state != null) {
                XposedBridge.hookMethod(state, new MyDevicesStateHook());
                XposedBridge.log(TAG + ": hooked AirpodsBluetoothManager." + state.getName()
                        + "(state,device)");
            } else {
                XposedBridge.log(TAG + ": WARN no stateUpdate/I(String,BluetoothDevice)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ERROR hooking state dispatcher: " + t);
        }
        // User disconnect: v17 disconnectAirpods(), legacy o().
        try {
            Method disc = findMethod(btManager, new String[]{"disconnectAirpods", "o"});
            if (disc != null) {
                XposedBridge.hookMethod(disc, new MyDevicesUserDisconnectHook());
                XposedBridge.log(TAG + ": hooked AirpodsBluetoothManager." + disc.getName()
                        + "() [user disconnect]");
            } else {
                XposedBridge.log(TAG + ": WARN no disconnectAirpods/o()");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ERROR hooking user disconnect: " + t);
        }
    }

    /** Function #2: force the noise-control "Off" option to be available. */
    private static void hookMyDevicesOffOption(ClassLoader cl) {
        Class<?> activity = loadFirst(cl, MYDEVICES_AIRPODS_SETTINGS_V17, MYDEVICES_AIRPODS_SETTINGS_LEGACY);
        if (activity == null) {
            XposedBridge.log(TAG + ": ERROR AirpodsSettingActivity not found (v17/legacy)");
            return;
        }
        boolean legacy = MYDEVICES_AIRPODS_SETTINGS_LEGACY.equals(activity.getName());
        // Feature-support mask method: v17 m2(int), legacy q2(int).
        try {
            Method fm = findMethod(activity, new String[]{"m2", "q2"}, int.class);
            if (fm != null) {
                XposedBridge.hookMethod(fm, new ForceOffSupportHook(legacy));
                XposedBridge.log(TAG + ": hooked " + activity.getName() + "." + fm.getName()
                        + "(int) [force Off 0x200]");
            } else {
                XposedBridge.log(TAG + ": ERROR no m2/q2(int) on " + activity.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ERROR hooking feature mask: " + t);
        }
        // Legacy UI also needed the Off row View forced visible after refresh.
        // v17 rebuilds its selector from the mask, so this is legacy-only.
        if (legacy) {
            hookLegacyOffRowRefresh(activity);
        }
    }

    private static void hookLegacyOffRowRefresh(Class<?> activity) {
        for (String name : new String[]{"r2", "h2"}) {
            try {
                Method m = (name.equals("h2"))
                        ? activity.getDeclaredMethod(name, String.class)
                        : activity.getDeclaredMethod(name);
                XposedBridge.hookMethod(m, new ForceLegacyOffRowHook());
            } catch (Throwable ignore) {
            }
        }
    }

    private static void hookBluetoothSocketOutputStream() {
        try {
            Class<?> socketClass = Class.forName("android.bluetooth.BluetoothSocket");
            Method getOutputStream = socketClass.getDeclaredMethod("getOutputStream");
            XposedBridge.hookMethod(getOutputStream, new BluetoothOutputStreamHook());
            XposedBridge.log(TAG + ": hooked BluetoothSocket.getOutputStream");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ERROR hooking BluetoothSocket.getOutputStream: " + t);
        }
    }

    private boolean tryKnownLoaders(ClassLoader cl) {
        ClassLoader cur = cl;
        while (cur != null) {
            if (tryHookVia(cur, "loader-chain")) return true;
            cur = cur.getParent();
        }
        return tryHookVia(ClassLoader.getSystemClassLoader(), "system-loader");
    }

    private boolean tryHookVia(ClassLoader cl, String where) {
        try {
            installWriterHooks(cl.loadClass(CLS));
            XposedBridge.log(TAG + ": resolved target via " + where + " " + cl);
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static synchronized void addWatcher(XC_MethodHook.Unhook unhook) {
        if (unhook != null) sWatchers.add(unhook);
    }

    private static synchronized void installWriterHooks(Class<?> cls) {
        if (sHooked) return;
        int hooked = 0;
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 2 && ps[0] == OutputStream.class && ps[1] == byte[].class) {
                try {
                    XposedBridge.hookMethod(m, WRITE_HOOK);
                    hooked++;
                    XposedBridge.log(TAG + ": hooked writer " + m.getName());
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": hook fail " + m.getName() + " " + t);
                }
            }
        }
        sHooked = true;
        XposedBridge.log(TAG + ": INSTALLED on AirPodsManager, writer hooks=" + hooked);
        for (XC_MethodHook.Unhook watcher : sWatchers) {
            try { watcher.unhook(); } catch (Throwable ignore) {}
        }
        sWatchers.clear();
    }

    // ---- reflection helpers ------------------------------------------------

    private static Class<?> loadFirst(ClassLoader cl, String... names) {
        for (String n : names) {
            try { return cl.loadClass(n); } catch (Throwable ignore) {}
        }
        return null;
    }

    private static Method findMethod(Class<?> cls, String[] names, Class<?>... params) {
        for (String n : names) {
            try {
                Method m = cls.getDeclaredMethod(n, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignore) {}
        }
        return null;
    }

    private static Field findField(Class<?> cls, String... names) {
        for (String n : names) {
            try {
                Field f = cls.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignore) {}
        }
        return null;
    }

    // ---- bluetooth AAP packet blocking (function #1, unchanged) -----------

    private static final class ClassResultWatcher extends XC_MethodHook {
        private final String source;

        ClassResultWatcher(String source) {
            this.source = source;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (sHooked) return;
            Object r = param.getResult();
            if (r instanceof Class && CLS.equals(((Class<?>) r).getName())) {
                XposedBridge.log(TAG + ": saw target from " + source);
                installWriterHooks((Class<?>) r);
            }
        }
    }

    private static final class WriteHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 2) return;
                Object a1 = param.args[1];
                if (!(a1 instanceof byte[])) return;
                byte[] d = (byte[]) a1;
                // AAP settings frame: 04 00 04 00 09 00 <settingId> <value> ...
                // settingId 0x34 == "off listening mode" -> DISABLE_OFF_LISTENING_MODE
                if (isDisableOffPacket(d, 0, d.length)) {
                    param.setResult(null); // skip out.write(d): keep Off available
                    XposedBridge.log(TAG + ": BLOCKED DISABLE_OFF_LISTENING_MODE " + hex(d));
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": write hook err " + t);
            }
        }
    }

    private static final class BluetoothOutputStreamHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object r = param.getResult();
                if (r instanceof OutputStream && !(r instanceof BlockingOutputStream)) {
                    param.setResult(new BlockingOutputStream((OutputStream) r));
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": getOutputStream hook err " + t);
            }
        }
    }

    // ---- MyDevices Off-option hooks (function #2) -------------------------

    /**
     * Force the "noise off supported" bit (0x200) into the feature mask before
     * the activity parses it, so the noise-control selector includes "Off".
     */
    private static final class ForceOffSupportHook extends XC_MethodHook {
        private final boolean legacy;

        ForceOffSupportHook(boolean legacy) {
            this.legacy = legacy;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 1
                        || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int mask = ((Integer) param.args[0]).intValue();
                if ((mask & FEATURE_NOISE_OFF) == 0) {
                    param.args[0] = Integer.valueOf(mask | FEATURE_NOISE_OFF);
                    XposedBridge.log(TAG + ": forced Off support mask 0x"
                            + Integer.toHexString(mask) + " -> 0x"
                            + Integer.toHexString(mask | FEATURE_NOISE_OFF));
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": force Off support err " + t);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            // v17 rebuilds its selector from the patched mask inside the method;
            // only the legacy UI needs the Off row View forced visible.
            if (!legacy) return;
            try {
                forceLegacyOffRowVisible(param.thisObject);
            } catch (Throwable ignore) {
            }
        }
    }

    private static final class ForceLegacyOffRowHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                forceLegacyOffRowVisible(param.thisObject);
            } catch (Throwable ignore) {
            }
        }
    }

    private static void forceLegacyOffRowVisible(Object activity) throws Exception {
        if (activity == null) return;
        Method offRowMethod = activity.getClass().getMethod("e1");
        Object offRow = offRowMethod.invoke(activity);
        if (offRow instanceof android.view.View) {
            android.view.View row = (android.view.View) offRow;
            row.setVisibility(android.view.View.VISIBLE);
            row.setEnabled(true);
            row.setClickable(true);
            XposedBridge.log(TAG + ": forced legacy MyDevices Off row visible/clickable");
        }
    }

    // ---- MyDevices connection-state hooks (function #3) -------------------

    /**
     * AirPods drop the idle HFP profile a few seconds after connect; the
     * MyDevices broadcast receiver maps that single-profile disconnect onto the
     * whole device. Drop the bogus STATE_DISCONNECTED while the device is really
     * still connected, but honor a disconnect the user just asked for.
     */
    private static final class MyDevicesStateHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 1
                        || !(param.args[0] instanceof String)) {
                    return;
                }
                String state = (String) param.args[0];
                if ("STATE_CONNECTED".equals(state) || "STATE_BONDED".equals(state)) {
                    sMyDevicesUserDisconnectAtMs = 0L;
                    return;
                }
                if (!"STATE_DISCONNECTED".equals(state)) {
                    return;
                }
                if (isRecent(sMyDevicesUserDisconnectAtMs, MYDEVICES_USER_DISCONNECT_WINDOW_MS)) {
                    XposedBridge.log(TAG + ": allowing user-initiated MyDevices STATE_DISCONNECTED");
                    return;
                }
                if (isMyDevicesManagerCurrentDeviceConnected(param.thisObject)) {
                    param.setResult(null);
                    XposedBridge.log(TAG + ": blocked false MyDevices STATE_DISCONNECTED"
                            + " (device still connected)");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": MyDevices state hook err " + t);
            }
        }
    }

    private static final class MyDevicesUserDisconnectHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            sMyDevicesUserDisconnectAtMs = System.currentTimeMillis();
            XposedBridge.log(TAG + ": MyDevices user requested disconnect;"
                    + " honoring STATE_DISCONNECTED for next "
                    + MYDEVICES_USER_DISCONNECT_WINDOW_MS + "ms");
        }
    }

    private static boolean isRecent(long timestamp, long windowMs) {
        return timestamp > 0L && System.currentTimeMillis() - timestamp <= windowMs;
    }

    private static boolean isMyDevicesManagerCurrentDeviceConnected(Object manager) {
        if (manager == null) return false;
        // v17.1.5: AirpodsBluetoothManager has a public isConnected() (ACL-level
        // via the OplusBluetoothDevice wrapper) -- use the app's own logic.
        try {
            Method isConnected = manager.getClass().getMethod("isConnected");
            Object r = isConnected.invoke(manager);
            if (r instanceof Boolean) return ((Boolean) r).booleanValue();
        } catch (Throwable ignore) {
        }
        // legacy fallback: wrap the device field in the Oplus wrapper.
        try {
            Field deviceField = findField(manager.getClass(), "device", "d");
            if (deviceField == null) return false;
            Object device = deviceField.get(manager);
            if (device == null) return false;
            Class<?> wrapperClass = manager.getClass().getClassLoader()
                    .loadClass("com.oplus.wrapper.bluetooth.BluetoothDevice");
            Object wrapper = wrapperClass.getConstructor(android.bluetooth.BluetoothDevice.class)
                    .newInstance(device);
            Method isConnected = wrapperClass.getMethod("isConnected");
            Object connected = isConnected.invoke(wrapper);
            return connected instanceof Boolean && ((Boolean) connected).booleanValue();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": MyDevices manager connection check err " + t);
            return false;
        }
    }

    // ---- AAP frame helpers (function #1) ----------------------------------

    private static final class BlockingOutputStream extends FilterOutputStream {
        BlockingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b) throws java.io.IOException {
            if (isDisableOffPacket(b, 0, b == null ? 0 : b.length)) {
                XposedBridge.log(TAG + ": BLOCKED DISABLE_OFF_LISTENING_MODE via BluetoothSocket " + hex(b));
                return;
            }
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            if (isDisableOffPacket(b, off, len)) {
                XposedBridge.log(TAG + ": BLOCKED DISABLE_OFF_LISTENING_MODE via BluetoothSocket " + hex(b, off, len));
                return;
            }
            out.write(b, off, len);
        }
    }

    private static boolean isDisableOffPacket(byte[] d, int off, int len) {
        if (d == null || len != 11 || off < 0 || off + len > d.length) return false;
        return (d[off] & 0xff) == 0x04
                && (d[off + 1] & 0xff) == 0x00
                && (d[off + 2] & 0xff) == 0x04
                && (d[off + 3] & 0xff) == 0x00
                && (d[off + 4] & 0xff) == 0x09
                && (d[off + 5] & 0xff) == 0x00
                && (d[off + 6] & 0xff) == 0x34
                && (d[off + 7] & 0xff) == 0x02
                && (d[off + 8] & 0xff) == 0x00
                && (d[off + 9] & 0xff) == 0x00
                && (d[off + 10] & 0xff) == 0x00;
    }

    private static String hex(byte[] d) {
        return hex(d, 0, d == null ? 0 : d.length);
    }

    private static String hex(byte[] d, int off, int len) {
        StringBuilder sb = new StringBuilder();
        if (d == null) return "";
        for (int i = off; i < off + len && i < d.length; i++) {
            sb.append(String.format("%02x ", d[i] & 0xff));
        }
        return sb.toString().trim();
    }
}
