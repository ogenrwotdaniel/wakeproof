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

/** Photographing the summary a focus session asks for at its end. */
@Serializable
data object SummaryCaptureRoute

/** The library of session-summary photos. */
@Serializable
data object SessionSummariesRoute

