package com.deepseek.chat.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.deepseek.chat.data.ImageRef
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeepSeekApp(state: ChatUiState, vm: ChatViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) vm.addImage(uri)
    }
    val imageLoader: (ImageRef, Int) -> android.graphics.Bitmap? = { ref, maxEdge ->
        vm.bitmapOf(ref, maxEdge)
    }

    BackHandler(enabled = state.showSettings) { vm.setShowSettings(false) }
    BackHandler(enabled = !state.showSettings && drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // 只有真正打开抽屉时才去读磁盘上的会话列表（启动阶段不碰）
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) vm.loadHistory()
    }

    if (state.showSettings) {
        SettingsScreen(
            settings = state.settings,
            onChange = vm::updateSettings,
            onTestMcp = { vm.testMcp() },
            onBack = { vm.setShowSettings(false) }
        )
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                conversations = state.conversations,
                currentId = state.conversation?.id,
                onSelect = { id ->
                    vm.openConversation(id)
                    scope.launch { drawerState.close() }
                },
                onNew = {
                    vm.newConversation()
                    scope.launch { drawerState.close() }
                },
                onDelete = { id -> vm.deleteConversation(id) },
                onRename = { id, title -> vm.renameConversation(id, title) },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    vm.setShowSettings(true)
                },
                onClearAll = { vm.deleteAllConversations() }
            )
        }
    ) {
        ChatScreen(
            state = state,
            imageLoader = imageLoader,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onNewChat = { vm.newConversation() },
            onInput = { text -> vm.setInput(text) },
            onSend = { vm.send() },
            onStop = { vm.stop() },
            onPickImage = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onRemoveImage = { ref -> vm.removeImage(ref) },
            onRegenerate = { vm.regenerate() },
            onDismissError = { vm.clearError() },
            onClearNotice = { vm.clearNotice() },
            onSetModel = { model -> vm.setModel(model) },
            onSetReasoning = { level -> vm.setReasoning(level) }
        )
    }
}
