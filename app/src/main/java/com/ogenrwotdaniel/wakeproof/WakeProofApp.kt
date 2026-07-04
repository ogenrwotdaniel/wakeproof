package com.ogenrwotdaniel.wakeproof

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Hosts the Hilt dependency graph for the whole app.
 */
@HiltAndroidApp
class WakeProofApp : Application()
