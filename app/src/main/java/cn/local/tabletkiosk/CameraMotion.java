package cn.local.tabletkiosk;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 前置摄像头画面变化检测：只在本机分析低分辨率亮度，画面不保存、不上传。 */
final class CameraMotion {
    /** 在主线程回调。 */
    interface Listener {
        /** 检测到画面变化。 */
        void onMotion();

        /** 前置摄像头在本进程里再也找不回来，需要重启应用。 */
        void onCameraLost();
    }

    private static final int GRID_COLS = 32;
    private static final int GRID_ROWS = 24;
    private static final long FRAME_INTERVAL_MS = 250;
    private static final long CHECK_MS = 5_000;

    private final ComponentActivity activity;
    private final Listener listener;
    private final LifecycleRegistry cameraLifecycle;
    private final LifecycleOwner cameraOwner = new LifecycleOwner() {
        /** 摄像头使用独立生命周期，息屏后主界面停止时仍保持打开。 */
        @Override
        public Lifecycle getLifecycle() {
            return cameraLifecycle;
        }
    };
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService analyzer = Executors.newSingleThreadExecutor();
    private final MotionDetector detector = new MotionDetector(0);
    private final CameraWatchdog watchdog = new CameraWatchdog();
    private final Runnable check = this::checkStalled;
    private ProcessCameraProvider provider;
    private ImageAnalysis analysis;
    private boolean wanted;
    /** 本进程成功绑定过前置摄像头，之后找不到才算丢失，避免没有前置摄像头的设备反复重启。 */
    private boolean everBound;
    private long lastFrame;
    /** 最近一帧到达时间，分析线程写、主线程读。 */
    private volatile long lastImage;
    private byte[] buffer = new byte[0];

    /** 摄像头绑定独立生命周期，不随主界面息屏而关闭。 */
    CameraMotion(ComponentActivity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        cameraLifecycle = new LifecycleRegistry(cameraOwner);
        cameraLifecycle.setCurrentState(Lifecycle.State.CREATED);
    }

    /** 打开摄像头并开始检测；已打开时只更新灵敏度。 */
    void start(int sensitivity) {
        detector.setSensitivity(sensitivity);
        if (cameraLifecycle.getCurrentState() != Lifecycle.State.RESUMED) {
            cameraLifecycle.setCurrentState(Lifecycle.State.RESUMED);
        }
        if (wanted) return;
        wanted = true;
        watchdog.reset(SystemClock.elapsedRealtime());
        main.postDelayed(check, CHECK_MS);
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(activity);
        future.addListener(() -> {
            try {
                provider = future.get();
                bind();
            } catch (Exception e) {
                Log.e(KioskApp.LOG, "Camera provider unavailable", e);
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    /** 关闭摄像头。 */
    void stop() {
        wanted = false;
        main.removeCallbacks(check);
        if (provider != null && analysis != null) provider.unbind(analysis);
        analysis = null;
        detector.reset();
        if (cameraLifecycle.getCurrentState() != Lifecycle.State.CREATED) {
            cameraLifecycle.setCurrentState(Lifecycle.State.CREATED);
        }
    }

    /** 释放分析线程，主界面销毁时调用。 */
    void release() {
        stop();
        analyzer.shutdown();
    }

    /** 绑定低分辨率图像分析到主界面生命周期。 */
    private void bind() {
        if (!wanted || provider == null || analysis != null) return;
        try {
            if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                Log.w(KioskApp.LOG, "Front camera not found; motion detection disabled");
                return;
            }
            ResolutionSelector resolution = new ResolutionSelector.Builder()
                    .setResolutionStrategy(new ResolutionStrategy(new Size(320, 240),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build();
            ImageAnalysis next = new ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
            next.setAnalyzer(analyzer, this::analyze);
            provider.bindToLifecycle(cameraOwner, CameraSelector.DEFAULT_FRONT_CAMERA, next);
            analysis = next;
            everBound = true;
            detector.reset();
            Log.i(KioskApp.LOG, "Front camera motion detection started");
        } catch (Exception e) {
            Log.e(KioskApp.LOG, "Failed to bind front camera", e);
        }
    }

    /**
     * 主线程定时检查，没有新画面时设法恢复。摄像头服务崩溃重启后，本进程的系统摄像头接口
     * 一直报告没有摄像头，重绑和重新初始化 CameraX 都找不回来，只能重启应用。
     */
    private void checkStalled() {
        if (!wanted) return;
        if (watchdog.shouldRebind(SystemClock.elapsedRealtime(), lastImage)) {
            if (everBound && !hasFrontCamera()) {
                // 系统不让重启（例如新版本 Android 在后台时）就按退避间隔再试
                Log.w(KioskApp.LOG, "Front camera lost after camera service restart, restarting app");
                listener.onCameraLost();
            } else {
                Log.w(KioskApp.LOG, "No camera frames, rebinding front camera");
                if (provider != null && analysis != null) provider.unbind(analysis);
                analysis = null;
                bind();
            }
        }
        main.postDelayed(check, CHECK_MS);
    }

    /** CameraX 现在还能不能看到前置摄像头。 */
    private boolean hasFrontCamera() {
        try {
            return provider != null && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA);
        } catch (CameraInfoUnavailableException e) {
            return false;
        }
    }

    /** 分析线程：限频后把 Y 平面缩成亮度网格，检测到变化时回到主线程通知。 */
    private void analyze(ImageProxy image) {
        try {
            long now = SystemClock.elapsedRealtime();
            lastImage = now;
            if (now - lastFrame < FRAME_INTERVAL_MS) return;
            lastFrame = now;
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer data = plane.getBuffer();
            int size = data.remaining();
            if (buffer.length != size) buffer = new byte[size];
            data.get(buffer);
            int[] cells = MotionDetector.downsample(buffer, image.getWidth(), image.getHeight(),
                    plane.getRowStride(), plane.getPixelStride(), GRID_COLS, GRID_ROWS);
            if (detector.process(cells)) main.post(listener::onMotion);
        } catch (RuntimeException e) {
            Log.e(KioskApp.LOG, "Camera frame analysis failed", e);
        } finally {
            image.close();
        }
    }
}
