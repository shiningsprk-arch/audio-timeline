package com.shiningsprk.audiotimeline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shiningsprk.audiotimeline.ui.App
import com.shiningsprk.audiotimeline.ui.AudioTimelineTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AudioTimelineTheme {
                App()
            }
        }
    }
}
