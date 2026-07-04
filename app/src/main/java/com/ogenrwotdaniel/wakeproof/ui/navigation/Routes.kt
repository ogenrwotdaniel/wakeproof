package com.ogenrwotdaniel.wakeproof.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe navigation routes for every screen in the app. */
@Serializable
data object HomeRoute

@Serializable
data object OnboardingRoute

/** [alarmId] is -1 until the alarm engine phase wires real alarm IDs through. */
@Serializable
data class AlarmRingingRoute(val alarmId: Long = -1L)

@Serializable
data object PushupVerifyRoute

@Serializable
data object WaterVerifyRoute

@Serializable
data object PomodoroRoute

@Serializable
data object SettingsRoute
