package com.tripsplit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw behind the system bars; Scaffold and the top bars pad for them.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TripSplitTheme {
                AppRoot()
            }
        }
    }
}
