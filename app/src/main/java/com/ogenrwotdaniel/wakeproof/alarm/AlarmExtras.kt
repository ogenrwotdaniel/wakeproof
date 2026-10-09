package com.ogenrwotdaniel.wakeproof.alarm

/** Intent extra: the [com.ogenrwotdaniel.wakeproof.data.db.Alarm] id being fired/shown. */
const val EXTRA_ALARM_ID = "com.ogenrwotdaniel.wakeproof.extra.ALARM_ID"

/** Intent extra: true when this firing is a developer test, not the daily schedule. */
const val EXTRA_TEST_FIRE = "com.ogenrwotdaniel.wakeproof.extra.TEST_FIRE"

/**
 * Intent extra, debug test fires only: an [com.ogenrwotdaniel.wakeproof.data.db.AlarmType]
 * name to verify with instead of the alarm's own type. Lets a mission be tried
 * on an existing alarm without creating a row that would sync to other devices.
 */
const val EXTRA_MISSION = "com.ogenrwotdaniel.wakeproof.extra.MISSION"
