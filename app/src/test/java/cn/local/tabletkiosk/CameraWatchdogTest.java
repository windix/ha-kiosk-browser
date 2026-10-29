package cn.local.tabletkiosk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 摄像头没有新画面时的重新绑定判断。 */
public class CameraWatchdogTest {
    private static final long S = 1000L;
    /** 单调时钟的起点，模拟开机已久的设备。 */
    private static final long T0 = 1_000_000L;

    /** 画面持续到来时不重绑。 */
    @Test
    public void framesKeepCameraAlive() {
        CameraWatchdog w = new CameraWatchdog();
        w.reset(T0);
        for (long t = T0; t <= T0 + 60 * S; t += 5 * S) {
            assertFalse(w.shouldRebind(t, t - 100));
        }
    }

    /** 画面停了 10 秒后要求重绑一次。 */
    @Test
    public void stalledFramesRequestRebind() {
        CameraWatchdog w = new CameraWatchdog();
        w.reset(T0);
        long last = T0 + 30 * S;
        assertFalse(w.shouldRebind(last + 9 * S, last));
        assertTrue(w.shouldRebind(last + 10 * S, last));
        assertFalse(w.shouldRebind(last + 11 * S, last));
    }

    /** 绑定后一直没有画面（摄像头没打开）也会重绑。 */
    @Test
    public void noFrameAfterStartRequestsRebind() {
        CameraWatchdog w = new CameraWatchdog();
        w.reset(T0);
        assertFalse(w.shouldRebind(T0 + 9 * S, 0));
        assertTrue(w.shouldRebind(T0 + 10 * S, 0));
    }

    /** 重绑后仍无画面，间隔逐次加倍，最长 5 分钟。 */
    @Test
    public void repeatedFailuresBackOff() {
        CameraWatchdog w = new CameraWatchdog();
        w.reset(T0);
        long t = T0 + 10 * S;
        assertTrue(w.shouldRebind(t, 0));
        long[] waits = {20, 40, 80, 160, 300, 300};
        for (long wait : waits) {
            assertFalse(w.shouldRebind(t + wait * S - 1, 0));
            t += wait * S;
            assertTrue(w.shouldRebind(t, 0));
        }
    }

    /** 重绑后画面恢复，下次卡住重新从 10 秒算起。 */
    @Test
    public void recoveryResetsBackoff() {
        CameraWatchdog w = new CameraWatchdog();
        w.reset(T0);
        assertTrue(w.shouldRebind(T0 + 10 * S, 0));
        assertTrue(w.shouldRebind(T0 + 30 * S, 0));
        long last = T0 + 31 * S;
        assertFalse(w.shouldRebind(last + 9 * S, last));
        assertTrue(w.shouldRebind(last + 10 * S, last));
    }

    /** 重新启动检测时清掉之前的失败次数。 */
    @Test
    public void resetClearsBackoff() {
        CameraWatchdog w = new CameraWatchdog();
        w.reset(T0);
        assertTrue(w.shouldRebind(T0 + 10 * S, 0));
        assertTrue(w.shouldRebind(T0 + 30 * S, 0));
        long again = T0 + 100 * S;
        w.reset(again);
        assertFalse(w.shouldRebind(again + 9 * S, 0));
        assertTrue(w.shouldRebind(again + 10 * S, 0));
    }
}
