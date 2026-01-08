package github.itsjarrett.flockyou

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class FlockCarService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        Log.d("FlockCarService", "Creating Host Validator. Debug mode: ${(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0}")
        // For development/sideloading, we must allow all hosts.
        // In production, you would whitelist the Android Auto host package.
        return if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session {
        Log.d("FlockCarService", "Creating Flock Car Session")
        return FlockCarSession()
    }
}