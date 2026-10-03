package sense.fixture;

import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** Standalone adb-shell test event source. Never linked into the product or fixture APK. */
public final class TouchBurst {
    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 2000 || args.length != 3) {
                throw new IllegalArgumentException("Expected adb shell UID and uid/interval/points arguments");
            }
            int uid = Integer.parseInt(args[0]);
            int interval = Integer.parseInt(args[1]);
            String[] points = args[2].split(",");
            if (uid < 10000 || interval < 16 || interval > 2000 || points.length < 1 || points.length > 96) {
                throw new IllegalArgumentException("Invalid bounded touch sequence");
            }
            float[][] positions = new float[points.length][2];
            for (int i = 0; i < points.length; i++) {
                String[] xy = points[i].split(":");
                if (xy.length != 2) throw new IllegalArgumentException("Expected x:y");
                for (int axis = 0; axis < 2; axis++) {
                    float value = Float.parseFloat(xy[axis]);
                    if (Float.isNaN(value) || Float.isInfinite(value) || value < 0 || value > 16384) {
                        throw new IllegalArgumentException("Invalid coordinate");
                    }
                    positions[i][axis] = value;
                }
            }
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            Class<?> type = Class.forName("android.hardware.input.InputManagerGlobal");
            Object manager = type.getMethod("getInstance").invoke(null);
            Method inject = type.getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
            JSONArray starts = new JSONArray();
            JSONArray actions = new JSONArray();
            for (int i = 0; i < points.length; i++) {
                long start = SystemClock.uptimeMillis();
                starts.put(start);
                for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                    MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                        positions[i][0], positions[i][1], 0);
                    event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                    long begin = SystemClock.elapsedRealtimeNanos();
                    boolean accepted;
                    try {
                        // NONE=0 queues delivery. The external editor separately verifies every prefix.
                        accepted = (Boolean) inject.invoke(manager, event, 0, uid);
                    } finally { event.recycle(); }
                    long end = SystemClock.elapsedRealtimeNanos();
                    actions.put(new JSONObject().put("keyIndex", i).put("action", action)
                        .put("beginNs", begin).put("endNs", end).put("accepted", accepted));
                    if (!accepted) throw new IllegalStateException("InputManager did not accept event");
                    if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(8);
                }
                long rest = interval - (SystemClock.uptimeMillis() - start);
                if (rest > 0 && i + 1 < points.length) SystemClock.sleep(rest);
            }
            System.out.println(new JSONObject().put("status", "ok").put("sourceUid", 2000)
                .put("targetUid", uid).put("keyStartMs", starts).put("actions", actions));
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.out.println("TOUCH_BURST_FAILED:" + failure.getClass().getName());
            System.exit(1);
        }
    }
}
