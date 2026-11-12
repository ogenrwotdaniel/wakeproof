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

/** The day-plan alarm: photograph today's handwritten plan. */
@Serializable
data object PlanVerifyRoute

/** The review alarm: a run of due flashcards. */
@Serializable
data object ReviewVerifyRoute

@Serializable
data object MathVerifyRoute

@Serializable
data object PomodoroRoute

@Serializable
data object SettingsRoute

@Serializable
data object UsageRoute

@Serializable
data object AgendaRoute

@Serializable
data object FlashcardsRoute

@Serializable
data object ForestRoute

/** The evening review alarm: the day's plan judged, tomorrow's top three written. */
@Serializable
data object ShutdownVerifyRoute

/** The evening review opened from the library, outside an alarm. */
@Serializable
data object ShutdownNowRoute

/** The library of day-plan photos. */
@Serializable
data object DayPlansRoute

/** Photographing a plan from the library, outside an alarm. */
@Serializable
data object PlanCaptureRoute
