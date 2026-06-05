import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * Non-root AAP "single-shot + HOLD" mode keeper (production-shaped; app_process shell, NO su).
 *
 * Fixes the bad UX of the round-poller (BtFix), which reopened the channel ~2-4x/s and that churn
 * tripped ColorOS's AAP contention -> dropped the link ("connect->transparency->DISCONNECT->off").
 *
 * This version touches the channel MINIMALLY:
 *   - one socket per connection; read the current listening mode as the target for this connect
 *   - then HOLD that same socket open, passively reading telemetry; re-assert ONLY if it flips.
 *   - reconnect ONLY when the channel genuinely dies (real disconnect). No polling churn.
 *
 * Expected UX: native connect chime -> preserve the current AirPods listening mode, with NO mid-disconnect.
 *
 * Args: <addr> [runSeconds=300] [graceMs=8000] [connectDelayMs=0] [connectChime=0] [commandFile=/data/local/tmp/bthold_command]
 */
public class BtHold {
    static final String UUID_STR = "74ec2172-0bad-4d01-8f77-997b2be0722a";
    static final int PSM = 4097;

    static final byte[] HS    = { 0x00,0x00,0x04,0x00,0x01,0x00,0x02,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00 };
    static final byte[] FEAT  = { 0x04,0x00,0x04,0x00,0x4D,0x00,(byte)0xD7,0x00,0x00,0x00,0x00,0x00,0x00,0x00 };
    static final byte[] NOTIF = { 0x04,0x00,0x04,0x00,0x0F,0x00,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF };
    static byte[] ctl(int id, int v) { return new byte[]{ 0x04,0x00,0x04,0x00,0x09,0x00,(byte)id,(byte)v,0x00,0x00,0x00 }; }
    static final byte[] ALLOW_OFF = ctl(0x34, 0x01);
    static final byte[] MODES_OTA = ctl(0x1A, 0x07);
    static final int MODE_OFF = 1;

    static String modeName(int m){ switch(m){case 1:return "OFF";case 2:return "ANC";case 3:return "TRANSPARENCY";case 4:return "ADAPTIVE";default:return "?("+m+")";} }
    static int modeValue(String s) {
        if (s == null) return -1;
        s = s.trim();
        if ("1".equals(s) || "OFF".equalsIgnoreCase(s)) return 1;
        if ("2".equals(s) || "ANC".equalsIgnoreCase(s) || "NOISE".equalsIgnoreCase(s)) return 2;
        if ("3".equals(s) || "TRANSPARENCY".equalsIgnoreCase(s) || "TRANS".equalsIgnoreCase(s)) return 3;
        if ("4".equals(s) || "ADAPTIVE".equalsIgnoreCase(s)) return 4;
        return -1;
    }
    static long progStart; static long now(){ return System.nanoTime()/1_000_000L; } static long t(){ return now()-progStart; }

    static final Object LOCK = new Object();
    static volatile int lastMode = -1; static volatile boolean channelDead = false; static byte[] carry = new byte[0];
    static void resetTel(){ synchronized(LOCK){ lastMode=-1; channelDead=false; carry=new byte[0]; } }
    static void scan(byte[] chunk,int n){
        byte[] data; synchronized(LOCK){
            if(carry.length==0) data=Arrays.copyOf(chunk,n);
            else { data=new byte[carry.length+n]; System.arraycopy(carry,0,data,0,carry.length); System.arraycopy(chunk,0,data,carry.length,n);}
            carry=Arrays.copyOfRange(data,Math.max(0,data.length-3),data.length);
        }
        for(int i=0;i+3<data.length;i++) if(data[i]==0x09&&data[i+1]==0x00&&data[i+2]==0x0D) synchronized(LOCK){ lastMode=data[i+3]&0xFF; }
    }
    static boolean validMode(int m) { return m >= 1 && m <= 4; }
    static boolean waitMode(int target,int ms) throws InterruptedException { long e=now()+ms; while(now()<e&&lastMode!=target) Thread.sleep(20); return lastMode==target; }

    static int readCommandMode(String commandFile) {
        if (commandFile == null || commandFile.length() == 0) return -1;
        File f = new File(commandFile);
        if (!f.exists()) return -1;
        FileInputStream in = null;
        try {
            byte[] buf = new byte[64];
            in = new FileInputStream(f);
            int n = in.read(buf);
            if (n <= 0) return -1;
            return modeValue(new String(buf, 0, n));
        } catch (Throwable th) {
            System.out.println("[CMD] t=" + t() + "ms  read failed: " + th);
            return -1;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ig) {}
            try { f.delete(); } catch (Throwable ig) {}
        }
    }

    static volatile boolean loggedCtor=false;
    static BluetoothSocket makeSocket(BluetoothAdapter ad, BluetoothDevice dev, ParcelUuid uuid) throws Exception {
        final int T=3;
        Object[][] specs={ {dev,T,true,true,PSM,uuid}, {dev,T,1,true,true,PSM,uuid}, {ad,dev,T,true,true,PSM,uuid} };
        Class<?>[][] sig={
            {BluetoothDevice.class,int.class,boolean.class,boolean.class,int.class,ParcelUuid.class},
            {BluetoothDevice.class,int.class,int.class,boolean.class,boolean.class,int.class,ParcelUuid.class},
            {BluetoothAdapter.class,BluetoothDevice.class,int.class,boolean.class,boolean.class,int.class,ParcelUuid.class},
        };
        Exception last=null;
        for(int i=0;i<specs.length;i++){ try{ Constructor<?> c=BluetoothSocket.class.getDeclaredConstructor(sig[i]); c.setAccessible(true); BluetoothSocket s=(BluetoothSocket)c.newInstance(specs[i]); if(!loggedCtor){System.out.println("BT: socket ctor ("+sig[i].length+"-arg) OK"); loggedCtor=true;} return s; }catch(Throwable th){ last=(th instanceof Exception)?(Exception)th:new RuntimeException(th);} }
        throw last;
    }
    static Object buildAttr(int uid,String pkg){ try{ Class<?> b=Class.forName("android.content.AttributionSource$Builder"); Object bb=b.getConstructor(int.class).newInstance(uid); try{b.getMethod("setPackageName",String.class).invoke(bb,pkg);}catch(Throwable ig){} return b.getMethod("build").invoke(bb);}catch(Throwable th){return null;} }
    static Context sysCtx(){ try{ Class<?> at=Class.forName("android.app.ActivityThread"); Object th=at.getMethod("systemMain").invoke(null); return (Context)at.getMethod("getSystemContext").invoke(th);}catch(Throwable t){return null;} }
    static BluetoothAdapter buildAdapter() throws Exception {
        try{ BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter(); if(a!=null){System.out.println("BT: getDefaultAdapter() OK"); return a;} }catch(Throwable th){}
        IBinder b=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"bluetooth_manager");
        if(b==null) return null;
        Class<?> ibm=Class.forName("android.bluetooth.IBluetoothManager");
        Object mgr=Class.forName("android.bluetooth.IBluetoothManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,b);
        Object attr=buildAttr(android.os.Process.myUid(),"com.android.shell"); Context ctx=sysCtx();
        BluetoothAdapter ad=null; Class<?> as=Class.forName("android.content.AttributionSource");
        try{ Constructor<?> c=BluetoothAdapter.class.getDeclaredConstructor(ibm,Context.class,as); c.setAccessible(true); ad=(BluetoothAdapter)c.newInstance(mgr,ctx,attr); System.out.println("BT: built adapter (mgr,ctx,attr)"); }
        catch(Throwable t1){ try{ Constructor<?> c=BluetoothAdapter.class.getDeclaredConstructor(ibm,Context.class); c.setAccessible(true); ad=(BluetoothAdapter)c.newInstance(mgr,ctx);}catch(Throwable t2){System.out.println("BT: adapter ctor failed: "+t2);} }
        if(ad==null) return null;
        for(Field f:BluetoothAdapter.class.getDeclaredFields()) if(Modifier.isStatic(f.getModifiers())&&f.getType()==BluetoothAdapter.class){ try{f.setAccessible(true); f.set(null,ad);}catch(Throwable th){} }
        return ad;
    }

    // Hidden but available to shell: lets us wait for the native Bluetooth connection first,
    // so we don't open the AAP channel early enough to swallow the AirPods connect chime.
    static int btConnected(BluetoothDevice dev) {
        try {
            Boolean ok = (Boolean) BluetoothDevice.class.getMethod("isConnected").invoke(dev);
            return ok.booleanValue() ? 1 : 0;
        } catch (Throwable th) {
            return -1;
        }
    }

    static boolean waitNativeConnect(BluetoothDevice dev, long deadline, long connectDelayMs) throws InterruptedException {
        int known = btConnected(dev);
        if (known < 0) return true; // fallback to the old AAP-connect driven behavior
        long lastLog = 0;
        long waitStart = now();
        long fallbackMs = connectDelayMs > 0 ? Math.max(8000L, connectDelayMs + 3000L) : 1200L;
        while (now() < deadline) {
            if (btConnected(dev) == 1) return true;
            if (now() - lastLog >= 3000) {
                System.out.println("[BT-WAIT] t=" + t() + "ms  waiting for native Bluetooth connection");
                lastLog = now();
            }
            if (now() - waitStart >= fallbackMs) {
                System.out.println("[BT-WAIT] t=" + t() + "ms  isConnected() still false after " + fallbackMs + "ms; probing AAP anyway");
                return true;
            }
            Thread.sleep(300);
        }
        return false;
    }

    // Set the current listening mode. Off still uses the known unlock sequence; other modes use 0x0D directly.
    static String setMode(OutputStream os, int target) throws Exception {
        if (!validMode(target)) return null;
        if (target == MODE_OFF) {
            os.write(ALLOW_OFF); os.flush(); Thread.sleep(60);
            os.write(ctl(0x0D, target)); os.flush();
            if (waitMode(target, 700)) return "0x34";
            os.write(MODES_OTA); os.flush(); Thread.sleep(60);
            os.write(ALLOW_OFF); os.flush(); Thread.sleep(60);
            os.write(ctl(0x0D, target)); os.flush();
            return waitMode(target, 800) ? "0x34+0x1A" : null;
        }
        os.write(ctl(0x0D, target)); os.flush();
        return waitMode(target, 2500) ? "0x0D" : null;
    }

    public static void main(String[] args){
        progStart=now();
        try{
            try{ Looper.prepareMainLooper(); }catch(Throwable ig){}
            String addr=args[0]; int runSec=args.length>1?Integer.parseInt(args[1]):300;
            long graceMs=args.length>2?Long.parseLong(args[2]):8000L; // enforce Off only within this window after each connect; after it, respect manual mode changes
            long connectDelayMs=args.length>3?Long.parseLong(args[3]):0L; // delay AAP open after native BT connect, preserving the AirPods connect chime
            boolean connectChime=args.length>4&&Integer.parseInt(args[4])!=0;
            String commandFile=args.length>5?args[5]:"/data/local/tmp/bthold_command";
            System.out.println("BT: uid="+android.os.Process.myUid()+" addr="+addr+" runSec="+runSec
                +" graceMs="+graceMs+" connectDelayMs="+connectDelayMs
                +" connectChime="+connectChime+" commandFile="+commandFile
                +"\nBT: >>> native-connect delay + single-shot/HOLD preserve-current-mode keeper (no churn). Reconnect / restart BT to test UX. <<<");
            BluetoothAdapter ad=buildAdapter(); if(ad==null){System.out.println("BT: no adapter"); return;}
            BluetoothDevice dev=ad.getRemoteDevice(addr); String nm; try{nm=dev.getName();}catch(Throwable th){nm="<?>";}
            System.out.println("BT: device="+dev+" name="+nm);
            ParcelUuid uuid=ParcelUuid.fromString(UUID_STR);

            long deadline=now()+runSec*1000L; int cycles=0; long lastAbsent=0; int rememberedTarget=-1; boolean inNativeSession=false; int nativeSession=0; long lastEofAt=0;
            while(now()<deadline){
                boolean wasInNativeSession = inNativeSession;
                if (!waitNativeConnect(dev, deadline, connectDelayMs)) break;
                boolean nativeConnected = btConnected(dev) == 1;
                boolean newNativeSession = !wasInNativeSession || !nativeConnected;
                if (lastEofAt > 0 && now() - lastEofAt < 20000L) newNativeSession = false;
                if (newNativeSession) nativeSession++;
                inNativeSession = true;
                if (connectDelayMs > 0) {
                    System.out.println("[BT-ON] t=" + t() + "ms  native Bluetooth connected; delaying AAP open by " + connectDelayMs + "ms");
                    Thread.sleep(connectDelayMs);
                }
                // ---- BURST: rapid rounds win the channel + read current mode; keep the winning socket ----
                BluetoothSocket held=null; OutputStream heldOs=null; int m0Seen=-1; int targetMode=-1; String how="-"; int burstRounds=0; long burstStart=now();
                for(int b=0; b<14 && now()<deadline && held==null; b++){
                    BluetoothSocket sock=null; resetTel();
                    try{ sock=makeSocket(ad,dev,uuid); sock.connect(); }
                    catch(Throwable th){ if(sock!=null) try{sock.close();}catch(Throwable i){} if(now()-lastAbsent>=3000){System.out.println("[ABSENT] t="+t()+"ms  no channel (disconnected?)"); lastAbsent=now();} Thread.sleep(500); continue; }
                    burstRounds++;
                    final InputStream is=sock.getInputStream();
                    Thread rd=new Thread(new Runnable(){ public void run(){
                        try{
                            byte[] bb=new byte[2048]; int n;
                            while((n=is.read(bb))>=0) if(n>0) scan(bb,n);
                            channelDead=true;
                        }catch(Throwable ig){ channelDead=true; }
                    }});
                    rd.setDaemon(true); rd.start();
                    OutputStream o=sock.getOutputStream();
                    o.write(HS); o.flush(); o.write(FEAT); o.flush(); o.write(NOTIF); o.flush();
                    long e=now()+900; while(now()<e && lastMode==-1) Thread.sleep(20);
                    int m=lastMode;
                    if(m==-1){ try{sock.close();}catch(Throwable i){} Thread.sleep(120); continue; }   // zombie: quick retry
                    if(m0Seen==-1) m0Seen=m;
                    if(validMode(m)){ held=sock; heldOs=o; targetMode=m; how="captured"; break; }
                    try{sock.close();}catch(Throwable i){} Thread.sleep(120);
                }
                if(held==null){ if(burstRounds>0) System.out.println("[give-up] t="+t()+"ms  mode not captured in burst ("+burstRounds+" rounds); retry"); Thread.sleep(500); continue; }
                int pendingCommand = readCommandMode(commandFile);
                if (validMode(pendingCommand)) rememberedTarget = pendingCommand;
                if (validMode(rememberedTarget)) {
                    targetMode = rememberedTarget;
                    if (lastMode != targetMode) {
                        String r = setMode(heldOs, targetMode);
                        how = "remembered " + (r != null ? r : "pending");
                    } else {
                        how = "remembered";
                    }
                }
                cycles++;
                System.out.println("\n["+(newNativeSession?"ON":"REOPEN")+"] t="+t()+"ms  session#"+nativeSession+" cycle#"+cycles+"  reconnect mode="+modeName(m0Seen)+" -> target "+modeName(targetMode)+" via "+how
                        +"  (burst "+burstRounds+" round(s), "+(now()-burstStart)+"ms)");
                if (connectChime && newNativeSession) System.out.println("[CHIME] t="+t()+"ms  request app connect chime");

                // ---- HOLD: enforce captured mode only within the grace window after connect (catches the rebound,
                //      even a slightly delayed one); after grace, user changes become the new target. ----
                long cStart=now(), hb=now(); int fights=0; int candidateMode=-1; long candidateSince=0;
                while(now()<deadline){
                    Thread.sleep(300);
                    boolean alive; try{alive=held.isConnected();}catch(Throwable th){alive=false;} if(!alive || channelDead) break;
                    int m=lastMode;
                    int commandMode = readCommandMode(commandFile);
                    if (validMode(commandMode)) {
                        targetMode = commandMode;
                        rememberedTarget = commandMode;
                        String r = setMode(heldOs, targetMode);
                        System.out.println("[CMD] t="+t()+"ms  set target "+modeName(targetMode)+" -> "+(r!=null?("OK via "+r):"FAILED"));
                        continue;
                    }
                    if(validMode(m) && m!=targetMode){
                        if(now()-cStart < graceMs){
                            fights++; String r=setMode(heldOs, targetMode);
                            System.out.println("[FIX] t="+t()+"ms  rebound "+modeName(m)+" within grace -> re-assert "+modeName(targetMode)+" "+(r!=null?("OK via "+r):"FAILED"));
                        } else {
                            if (m != candidateMode) {
                                candidateMode = m;
                                candidateSince = now();
                            } else if (now() - candidateSince >= 2500L) {
                                targetMode=m;
                                rememberedTarget=m;
                                candidateMode=-1;
                                candidateSince=0;
                                System.out.println("[USER] t="+t()+"ms  stable manual "+modeName(m)+" after grace -> new target");
                            }
                        }
                    } else if(validMode(m) && m==targetMode) {
                        candidateMode=-1;
                        candidateSince=0;
                    }
                    if(now()-hb>=8000){
                        System.out.println("[HOLD] t="+t()+"ms  mode="+modeName(lastMode)+" target="+modeName(targetMode)+"  held="+(now()-cStart)+"ms  fights="+fights);
                        hb=now();                    }
                }
                try{held.close();}catch(Throwable i){}
                if(now()<deadline){
                    boolean stillConnected = btConnected(dev) == 1;
                    lastEofAt = now();
                    System.out.println("[EOF] t="+t()+"ms  channel closed (held "+(now()-cStart)+"ms); "+(stillConnected?"reopening AAP channel":"waiting for next native connect"));
                    Thread.sleep(300);
                }
            }
            System.out.println("\n===== done: "+cycles+" hold cycle(s) over "+runSec+"s =====");
        }catch(Throwable th){ System.out.println("BT: EXC "+th); for(Throwable c=th.getCause();c!=null;c=c.getCause()) System.out.println("BT: cause "+c); th.printStackTrace(); }
        System.exit(0);
    }
}
