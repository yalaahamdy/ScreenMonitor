package com.screenguard.app

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Custom Application class tracking process lifecycle and maintaining
 * secure parental session state across foreground / background transitions.
 */
class ScreenGuardApp : Application(), Application.ActivityLifecycleCallbacks {

    private var activityReferences = 0
    private var isActivityChangingConfigurations = false

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (++activityReferences == 1 && !isActivityChangingConfigurations) {
            // App entered foreground from background
            PinManager.onAppEnteredForeground()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        isActivityChangingConfigurations = activity.isChangingConfigurations
        if (--activityReferences <= 0 && !isActivityChangingConfigurations) {
            activityReferences = 0
            // App entered background
            PinManager.onAppEnteredBackground()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
