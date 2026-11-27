package com.deepseek.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.chat.data.ThemeMode
import com.deepseek.chat.ui.ChatViewModel
import com.deepseek.chat.ui.DeepSeekApp
import com.deepseek.chat.ui.theme.DeepSeekTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            // 应用级单例：Activity 被回收/重建后仍是同一个 ViewModel，
            // 进行中的生成和界面状态都不会丢（见 ChatEngine）
            val vm: ChatViewModel = ChatEngine.viewModel(application)
            val state by vm.state.collectAsStateWithLifecycle()

            // 退到后台/退出前把还在流式输出的思考过程留一份到磁盘：
            // 进程被系统回收后回来，历史里仍然能看到「已深度思考」。
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, vm) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) vm.saveStreamingCheckpoint()
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            val darkTheme = when (state.settings.theme) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> isSystemInDarkTheme()
            }

            DeepSeekTheme(darkTheme = darkTheme) {
                val background = MaterialTheme.colorScheme.background
                val view = LocalView.current
                SideEffect {
                    // 应用内主题可能与系统不同：状态栏/导航栏底色和图标颜色都要跟着应用走
                    view.context.findActivity()?.window?.let { window ->
                        @Suppress("DEPRECATION")
                        window.statusBarColor = background.toArgb()
                        @Suppress("DEPRECATION")
                        window.navigationBarColor = background.toArgb()
                        val controller = WindowCompat.getInsetsController(window, view)
                        controller.isAppearanceLightStatusBars = !darkTheme
                        controller.isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                DeepSeekApp(state = state, vm = vm)
            }
        }
    }
    /** Android 13+ 才需要运行时通知权限；Android 10 及以下直接跳过 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
