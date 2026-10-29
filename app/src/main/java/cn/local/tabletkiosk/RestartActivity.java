package cn.local.tabletkiosk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

/** 在独立进程里结束主进程再重新打开主界面，用于主进程状态坏掉、只能重启才能恢复的情况。 */
public final class RestartActivity extends Activity {
    private static final String EXTRA_PID = "pid";

    /** 从主进程调用，交给独立进程完成重启。 */
    static void restart(Context context) {
        context.startActivity(new Intent(context, RestartActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_PID, Process.myPid()));
    }

    /** 结束主进程，重新打开主界面后退出本进程。 */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pid = getIntent().getIntExtra(EXTRA_PID, 0);
        // pid 不能是 0 或负数，否则会把信号发给整组进程
        if (pid > 0) Process.killProcess(pid);
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
        Runtime.getRuntime().exit(0);
    }
}
