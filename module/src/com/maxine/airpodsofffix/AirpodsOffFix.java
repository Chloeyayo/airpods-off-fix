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
 * ColorOS 16 / AirPods Pro 3 fixes:
 *
 *  1. Keep "Off" noise-control available: block the AAP
 *     DISABLE_OFF_LISTENING_MODE frame (04 00 04 00 09 00 34 02 00 00 00) that
 *     ColorOS sends on connect, which otherwise forces Off -> Transparency.
 *     Primary path: wrap BluetoothSocket.getOutputStream() and drop that exact
 *     11-byte frame. Fallback path: watch for com.oplus.bluetooth.feature.airpods
 *     .AirPodsManager (loaded by a CHILD loader of com.android.bluetooth) and
 *     hook its (OutputStream, byte[]) writers.
 *
 *  2. Show "Off" in MyDevices: hook AirpodsSettingActivity.q2(int) to add the
 *     0x200 "noise reduction shutdown" feature bit, and force the Off row visible
 *     after q2/r2/h2.
 *
 *  3. Stop the bogus page grey-out: AirPods release the idle HFP link a few
 *     seconds after connect, and MyDevices treats any single-profile disconnect
 *     as a whole-device disconnect. Hook AirpodsBluetoothManager.I(state,device)
 *     and drop STATE_DISCONNECTED while the device is really still connected
 *     (A2DP/ACL up). A user-initiated disconnect (manager.o()) opens a short
 *     window in which STATE_DISCONNECTED is honored so the page greys out.
 *
 * Hook callbacks are NAMED static nested classes (not anonymous) to avoid a
 * d8/R8 internal crash on method-local anonymous classes.
 */
public class AirpodsOffFix implements IXposedHookLoadPackage {

    private static final String TAG = "AirpodsOffFix";
    private static final String CLS = "com.oplus.bluetooth.feature.airpods.AirPodsManager";
    private static final String MYDEVICES_PKG = "com.heytap.mydevices";
    private static final String MYDEVICES_AIRPODS_SETTINGS =
            "com.heytap.mydevices.core.ui.apple.AirpodsSettingActivity";
    private static final String MYDEVICES_AIRPODS_BT_MANAGER =
            "com.heytap.mydevices.core.bluetooth.AirpodsBluetoothManager";
    private static final int FEATURE_NOISE_OFF = 0x200;
    // After the user taps "Disconnect" in MyDevices we must let STATE_DISCONNECTED
    // through so the page greys out, even though BluetoothDevice.isConnected() can
    // briefly still report true while the A2DP/HFP/ACL links tear down.
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

    private static void hookMyDevicesAirPodsSettings(ClassLoader cl) {
        try {
            Class<?> btManager = cl.loadClass(MYDEVICES_AIRPODS_BT_MANAGER);
            Method setState = btManager.getDeclaredMethod("I", String.class,
                    android.bluetooth.BluetoothDevice.class);
            XposedBridge.hookMethod(setState, new MyDevicesBluetoothManagerStateHook());

            // o() == disconnectAirpods(): opens the "user really wants to disconnect" window.
            Method userDisconnect = btManager.getDeclaredMethod("o");
            XposedBridge.hookMethod(userDisconnect, new MyDevicesUserDisconnectHook());

            Class<?> activity = cl.loadClass(MYDEVICES_AIRPODS_SETTINGS);
            Method updateFeatureSupport = activity.getDeclaredMethod("q2", int.class);
            XposedBridge.hookMethod(updateFeatureSupport, new ForceMyDevicesOffModeHook());
            Method refreshDeviceInfo = activity.getDeclaredMethod("r2");
            XposedBridge.hookMethod(refreshDeviceInfo, new ForceMyDevicesOffRowAfterRefreshHook());
            Method updateModel = activity.getDeclaredMethod("h2", String.class);
            XposedBridge.hookMethod(updateModel, new ForceMyDevicesOffRowAfterRefreshHook());
            XposedBridge.log(TAG + ": hooked MyDevices AirpodsBluetoothManager.I/o"
                    + " and AirpodsSettingActivity.q2/r2/h2");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ERROR hooking MyDevices AirPods settings: " + t);
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

    private static boolean shouldPatchMyDevicesFeatureMask(Object activity, int mask) {
        if ((mask & FEATURE_NOISE_OFF) != 0) return false;
        return isMyDevicesOffModeTarget(activity, mask);
    }

    private static boolean isMyDevicesOffModeTarget(Object activity, int mask) {
        if ((mask & FEATURE_NOISE_OFF) != 0) return true;
        String model = getMyDevicesAirPodsModel(activity);
        return model != null && model.contains("AirPods Pro 3");
    }

    private static boolean isRecent(long timestamp, long windowMs) {
        return timestamp > 0L && System.currentTimeMillis() - timestamp <= windowMs;
    }

    private static boolean isMyDevicesManagerCurrentDeviceConnected(Object manager) {
        if (manager == null) return false;
        try {
            Field deviceField = manager.getClass().getDeclaredField("d");
            deviceField.setAccessible(true);
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

    private static String getMyDevicesAirPodsModel(Object activity) {
        if (activity == null) return null;
        try {
            Field modelField = activity.getClass().getDeclaredField("z0");
            modelField.setAccessible(true);
            Object model = modelField.get(activity);
            return model instanceof String ? (String) model : null;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static void forceMyDevicesOffRowVisible(Object activity) throws Exception {
        if (activity == null) return;
        Method offRowMethod = activity.getClass().getMethod("e1");
        Object offRow = offRowMethod.invoke(activity);
        if (offRow instanceof android.view.View) {
            android.view.View row = (android.view.View) offRow;
            row.setVisibility(android.view.View.VISIBLE);
            row.setEnabled(true);
            row.setClickable(true);
            XposedBridge.log(TAG + ": forced MyDevices Off mode row visible/clickable"
                    + " model=" + getMyDevicesAirPodsModel(activity));
        }
    }

    private static final class ForceMyDevicesOffModeHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int original = ((Integer) param.args[0]).intValue();
                if (!shouldPatchMyDevicesFeatureMask(param.thisObject, original)) {
                    return;
                }
                int patched = original | FEATURE_NOISE_OFF;
                if (patched != original) {
                    param.args[0] = Integer.valueOf(patched);
                    XposedBridge.log(TAG + ": patched MyDevices feature mask "
                            + original + "/0x" + Integer.toHexString(original)
                            + " -> " + patched + "/0x" + Integer.toHexString(patched));
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": patch feature mask err " + t);
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Object activity = param.thisObject;
                if (activity == null) return;
                if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int mask = ((Integer) param.args[0]).intValue();
                if (isMyDevicesOffModeTarget(activity, mask)) {
                    forceMyDevicesOffRowVisible(activity);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": force Off row err " + t);
            }
        }
    }

    private static final class ForceMyDevicesOffRowAfterRefreshHook extends XC_MethodHook {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (isMyDevicesOffModeTarget(param.thisObject, 0)) {
                    forceMyDevicesOffRowVisible(param.thisObject);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": refresh Off row err " + t);
            }
        }
    }

    /**
     * AirPods drop the idle HFP profile a few seconds after connect; MyDevices
     * maps that single-profile disconnect onto the whole device. Drop the bogus
     * STATE_DISCONNECTED while the device is really still connected, but honor a
     * disconnect the user just asked for (see MyDevicesUserDisconnectHook).
     */
    private static final class MyDevicesBluetoothManagerStateHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof String)) {
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
                    // User tapped Disconnect: let it through so the page greys out.
                    XposedBridge.log(TAG + ": allowing user-initiated MyDevices STATE_DISCONNECTED");
                    return;
                }
                if (isMyDevicesManagerCurrentDeviceConnected(param.thisObject)) {
                    param.setResult(null);
                    XposedBridge.log(TAG + ": blocked false MyDevices manager STATE_DISCONNECTED"
                            + " because BluetoothDevice.isConnected=true");
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": MyDevices manager state hook err " + t);
            }
        }
    }

    private static final class MyDevicesUserDisconnectHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            sMyDevicesUserDisconnectAtMs = System.currentTimeMillis();
            XposedBridge.log(TAG + ": MyDevices user requested disconnect (o);"
                    + " honoring STATE_DISCONNECTED for next "
                    + MYDEVICES_USER_DISCONNECT_WINDOW_MS + "ms");
        }
    }

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
