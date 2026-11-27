package com.deepseek.chat

import android.app.Application
import com.deepseek.chat.ui.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级运行环境（进程内单例）：
 *
 * 1. 一个共享的 [ChatViewModel]：Activity 销毁/重建（切后台、划掉最近任务）时状态不丢，
 *    回来还是同一个会话、同一段流式输出，而不是从零开一个新对话；
 * 2. 一个应用级 [CoroutineScope]：生成、MCP 工具调用跑在这里，不随 ViewModel 的
 *    viewModelScope 清理而被取消（用户主动点「停止」才会取消）。
 *
 * 进程真被系统杀掉时这层也没了，靠 ChatViewModel 的流式检查点保住已输出的部分。
 */
object ChatEngine {

    private val job = SupervisorJob()

    /** 生成/工具调用用的应用级作用域（不做 UI 调度，网络与磁盘都在这上面） */
    val scope: CoroutineScope = CoroutineScope(job + Dispatchers.IO)

    @Volatile
    private var sharedViewModel: ChatViewModel? = null

    fun viewModel(application: Application): ChatViewModel =
        sharedViewModel ?: synchronized(this) {
            sharedViewModel ?: ChatViewModel(application).also { sharedViewModel = it }
        }
}
