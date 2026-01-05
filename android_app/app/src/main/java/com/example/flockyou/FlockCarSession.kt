package com.example.flockyou

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class FlockCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return FlockCarScreen(carContext)
    }
}