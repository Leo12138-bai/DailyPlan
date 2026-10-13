package com.dailyplan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dailyplan.app.ui.AppViewModel
import com.dailyplan.app.ui.DailyPlanApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appViewModel: AppViewModel = viewModel()
            val lifecycleObserver = remember {
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) appViewModel.refresh()
                }
            }
            DisposableEffect(lifecycle) {
                lifecycle.addObserver(lifecycleObserver)
                onDispose { lifecycle.removeObserver(lifecycleObserver) }
            }
            DailyPlanApp(appViewModel)
        }
    }
}
