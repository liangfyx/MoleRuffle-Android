package rs.ruffle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * MoleRuffle:后台保活服务。
 *
 * 为什么需要:点「导出日志」会弹出系统分享面板,我们的窗口随即被销毁、
 * 应用进入后台 —— 这时系统很容易把进程回收,回来就"闪退/重新加载"。
 * 这里在分享期间(以及任何需要短暂离开前台的场景)用**前台服务**把进程
 * 优先级抬高,避免被杀;分享结束(回到前台)立刻停掉,不留常驻通知。
 */
class KeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val n: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setContentTitle("摩尔庄园运行中")
            setContentText("离开前台期间保持进程存活,回来即恢复")
            setSmallIcon(android.R.drawable.stat_sys_upload_done)
            setOngoing(true)
        }.build()

        // Android 12+ 后台启动前台服务会抛异常,这里兜住(真被限制也不至于崩)。
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (t: Throwable) {
            android.util.Log.w("ruffle", "MoleRuffle: 保活服务启动失败 $t")
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
                nm?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "后台保活",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "离开前台(如导出日志)时保持进程不被回收" }
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            @Suppress("DEPRECATION")
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "mole_keepalive"
        private const val NOTIF_ID = 9021
    }
}
