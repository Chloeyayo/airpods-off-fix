package de.robv.android.xposed;
import java.lang.reflect.Member;
// COMPILE-ONLY STUB. Provided at runtime by LSPosed. Not packaged into the dex.
// Signatures MUST match the real Xposed API descriptors exactly.
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class Unhook {
        public void unhook() {}
    }

    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Object getResult() { return null; }
        public void setResult(Object result) {}
        public Throwable getThrowable() { return null; }
        public boolean hasThrowable() { return false; }
        public void setThrowable(Throwable throwable) {}
        public Object getResultOrThrowable() throws Throwable { return null; }
    }
}
