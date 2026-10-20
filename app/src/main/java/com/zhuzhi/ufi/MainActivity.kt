package com.zhuzhi.ufi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zhuzhi.ufi.ui.UfiApp
import com.zhuzhi.ufi.ui.UfiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialTab = intent.getIntExtra("tab", 0)
        setContent {
            UfiTheme {
                val vm: UfiViewModel = viewModel()
                LaunchedEffect(Unit) {
                    if (initialTab in 0..2) vm.setTab(initialTab)
                }
                UfiApp(vm)
            }
        }
    }
}
