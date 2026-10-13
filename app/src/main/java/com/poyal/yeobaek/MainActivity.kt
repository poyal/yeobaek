package com.poyal.yeobaek

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.poyal.yeobaek.ui.*

class MainActivity : ComponentActivity() {
    private var model: AppViewModel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            val vm: AppViewModel = viewModel()
            model = vm
            val app by vm.app.collectAsStateWithLifecycle()
            YeobaekTheme(app.theme) { YeobaekApp(vm) }
        }
    }
    override fun onStop() { model?.background(); super.onStop() }
}
