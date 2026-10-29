package cn.local.tabletkiosk;

/** 摄像头一段时间没有新画面时要求重新绑定；重绑后仍无画面则逐次拉长间隔。 */
public final class CameraWatchdog {
    private static final long STALL_MS = 10_000;
    private static final long MAX_WAIT_MS = 300_000;
    private static final int MAX_FAILURES = 5;
    private long boundAt;
    private int failures;

    /** 开始检测，从现在起计时并清掉失败次数。 */
    public void reset(long now) {
        boundAt = now;
        failures = 0;
    }

    /**
     * 到点时返回 true，调用方随即重新绑定，从现在起重新计时。
     *
     * @param now 当前单调时钟毫秒
     * @param lastFrame 最近一帧画面到达的单调时钟毫秒，还没有画面时为 0
     */
    public boolean shouldRebind(long now, long lastFrame) {
        // 上次绑定后来过画面，说明已经恢复
        if (lastFrame > boundAt) failures = 0;
        long limit = Math.min(STALL_MS << failures, MAX_WAIT_MS);
        if (now - Math.max(boundAt, lastFrame) < limit) return false;
        failures = Math.min(failures + 1, MAX_FAILURES);
        boundAt = now;
        return true;
    }
}
