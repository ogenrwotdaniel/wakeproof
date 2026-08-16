package com.ogenrwotdaniel.wakeproof.ui

import android.content.Context
import android.text.format.DateFormat
import java.util.Calendar

/**
 * Alarm times follow the phone's 12/24-hour setting, everywhere.
 *
 * They used to be hardcoded to 24: the pickers were built with `is24Hour = true`
 * and the labels with `"%02d:%02d"`. Someone whose phone is set to 12-hour reads
 * a 24-hour clock face as a 12-hour one — wanting 6 PM they pick 6, the alarm is
 * stored as 06:00, and it rings in the morning. The app looked right on any dev
 * device already set to 24-hour, which is why it took a tester to find.
 */
fun deviceUses24HourClock(context: Context): Boolean = DateFormat.is24HourFormat(context)

/** [hour]:[minute] written the way this device writes times ("17:30" or "5:30 PM"). */
fun formatAlarmTime(context: Context, hour: Int, minute: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }
    return DateFormat.getTimeFormat(context).format(calendar.time)
}
