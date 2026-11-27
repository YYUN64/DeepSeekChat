package com.deepseek.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 一轮生成期间的前台服务：只为保证进程优先级。
 *
 * 覆盖**整轮**：连接 MCP → 流式生成 → 调用工具 → 整理答案，文案按阶段更新
 * （[update] 直接改通知，不再额外启服务，后台也不会被 Service 启动限制拦住）。
 *
 * 通知仍是最低可见形态：IMPORTANCE_MIN 渠道、静默、无按钮、不可划掉。
 * 任务被划掉（onTaskRemoved）时**不**停止服务，正在跑的工具调用/生成要继续。
 */
class ChatService : Service() {

    companion object {
        private const val CHANNEL_ID = "chat_generation"
        private const val CHANNEL_NAME = "生成回答"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_TEXT = "text"

        const val PHASE_CONNECTING_MCP = "正在连接 MCP…"
        const val PHASE_GENERATING = "正在生成回答…"
        const val PHASE_FINISHING = "正在整理答案…"

        /** 调用工具：run_command → 「正在调用工具：run_command」 */
        fun phaseTool(name: String): String {
            val tool = name.ifBlank { "工具" }
            return "正在调用工具：$tool"
        }

        /** 启动/重新声明前台服务，同时把文案换成 [text] */
        fun start(context: Context, text: String = PHASE_GENERATING) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ChatService::class.java).putExtra(EXTRA_TEXT, text)
                )
            }
        }

        /**
         * 只更新通知文案。用 NotificationManager 直接改同一个 id，
         * 避免在后台再启一次 Service（Android 12+ 后台启动 Service 有限制）。
         */
        fun update(context: Context, text: String) {
            runCatching {
                val manager = NotificationManagerCompat.from(context)
                if (!manager.areNotificationsEnabled()) return
                manager.notify(NOTIFICATION_ID, buildNotification(context, text))
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, ChatService::class.java))
            }
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
            )
        }

        private fun buildNotification(context: Context, text: String): Notification {
            createChannel(context)
            val appContext = context.applicationContext
            val openApp = PendingIntent.getActivity(
                appContext,
                0,
                Intent(appContext, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("DeepSeek Lite")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp)
                .build()
        }
    }

    private var lastText: String = PHASE_GENERATING

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { lastText = it }
        startForeground(NOTIFICATION_ID, buildNotification(this, lastText))
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 任务被划掉时**不停止**：正在执行的 MCP 工具调用和生成必须跑完，
        // 真正结束时由 ChatViewModel 调 stop() 收掉服务。
        super.onTaskRemoved(rootIntent)
    }
}
