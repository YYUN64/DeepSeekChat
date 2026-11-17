package com.deepseek.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepseek.chat.ui.ChatViewModel
import com.deepseek.chat.ui.DeepSeekApp
import com.deepseek.chat.ui.theme.DeepSeekTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: ChatViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            DeepSeekTheme {
                DeepSeekApp(state = state, vm = vm)
            }
        }
    }
}
