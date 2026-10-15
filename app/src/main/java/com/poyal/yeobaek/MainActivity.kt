package com.poyal.yeobaek

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import android.view.MotionEvent
import com.poyal.yeobaek.diagnostics.ScrollTouchTrace
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.poyal.yeobaek.ui.*

class MainActivity : ComponentActivity() {
    private var model: AppViewModel? = null
    private val scrollTouchTrace by lazy { ScrollTouchTrace((application as YeobaekApplication).scrollDiagnostics) }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val state = model?.ui?.value
        val session = state?.reader?.token?.takeIf { state.screen != "txt_settings" && state.sheet == null && state.error == null }
        scrollTouchTrace.touch(event, session)
        return super.dispatchTouchEvent(event)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        setContent {
            val vm: AppViewModel = viewModel()
            model = vm
            LaunchedEffect(vm) { if (savedInstanceState == null) vm.openExternal(intent) }
            val app by vm.app.collectAsStateWithLifecycle()
            YeobaekTheme(app.theme) { YeobaekApp(vm) }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); model?.openExternal(intent) }
    override fun onResume() { super.onResume(); model?.foreground() }
    override fun onStop() { scrollTouchTrace.finish("background"); model?.background(); super.onStop() }
}
