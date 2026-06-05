package com.maxine.airpodsofffix.shizuku;

import java.lang.reflect.Method;

/**
 * Exempt hidden / blocklisted platform APIs (specifically the reflected classic
 * L2CAP {@code BluetoothSocket} constructor that AapOff needs) inside a normal
 * app process.
 *
 * <p>The Shizuku build never needed this: it ran in the shell uid, which is
 * globally exempt from the hidden-API policy. A plain app is not, so we exempt
 * ourselves via the "double reflection" bootstrap: by calling
 * {@code getDeclaredMethod} <em>through</em> {@link Method#invoke}, the apparent
 * caller of {@code getDeclaredMethod} becomes a bootclasspath class
 * ({@code java.lang.reflect.Method}), which the runtime treats as trusted and
 * therefore lets it resolve the otherwise-hidden
 * {@code VMRuntime.setHiddenApiExemptions} method.
 *
 * <p>Reliable on Android 9-13; needs on-device verification on 14+ (ColorOS
 * V16.1.0). If it ever stops working, the fallback is to vendor
 * {@code org.lsposed.hiddenapibypass} (pure Java, fits this javac pipeline).
 */
public final class HiddenApi {
    private HiddenApi() {}

    /** @return true if the exemption call completed without throwing. */
    public static boolean exempt() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);

            Class<?> vmRuntimeClass = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(
                    vmRuntimeClass, "getRuntime", new Class<?>[0]);
            Method setExemptions = (Method) getDeclaredMethod.invoke(
                    vmRuntimeClass, "setHiddenApiExemptions", new Class<?>[]{ String[].class });

            Object vmRuntime = getRuntime.invoke(null);
            // "L" is the JNI descriptor prefix shared by every class -> exempt all hidden APIs.
            setExemptions.invoke(vmRuntime, new Object[]{ new String[]{ "L" } });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
