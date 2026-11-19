package com.personal.dynamicpill

import android.app.AlarmManager
import android.content.Context
import android.icu.util.Calendar
import android.icu.util.IslamicCalendar
import android.os.BatteryManager
import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.Month
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** What the idle pill shows for each [IdleWidget]: glances beside the camera and an animated effect. */
class IdleDecorations(private val context: Context) {

    /** [ticking] decorations show the time, date or battery and must be refreshed while on screen. */
    data class Decoration(
        val left: Glance = Glance.None,
        val right: Glance = Glance.None,
        val effect: PillEffect = PillEffect.NONE,
        val ticking: Boolean = false,
    )

    fun forWidget(widget: IdleWidget, now: ZonedDateTime = ZonedDateTime.now()): Decoration {
        EFFECT_ONLY[widget]?.let { return Decoration(effect = it) }
        return when (widget) {
            IdleWidget.EYES -> Decoration(Glance.Eye(), Glance.Eye())
            IdleWidget.BLUSH -> Decoration(Glance.Eye(happy = true), Glance.Eye(happy = true), PillEffect.BLUSH)
            IdleWidget.CLOCK -> clock(now)
            IdleWidget.DATE -> Decoration(text(now.format(MONTH_FORMAT)), text(now.format(DAY_FORMAT)), ticking = true)
            IdleWidget.BATTERY -> battery()
            IdleWidget.NEXT_ALARM -> nextAlarm(now)
            IdleWidget.MOON -> moon(now)
            IdleWidget.WEEKEND -> weekend(now)
            IdleWidget.HOLIDAY -> holiday(now)
            IdleWidget.EMOJI_OF_THE_DAY -> emojiOfTheDay(now)
            else -> Decoration()
        }
    }

    private fun clock(now: ZonedDateTime) =
        Decoration(text(now.format(WEEKDAY_FORMAT)), text(now.format(timeFormat())), ticking = true)

    private fun battery(): Decoration {
        val battery = context.getSystemService(BatteryManager::class.java)
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val symbol = if (battery.isCharging) CHARGING_SYMBOL else BATTERY_SYMBOL
        return Decoration(text(symbol), text(context.getString(R.string.charging_percent, percent)), ticking = true)
    }

    private fun nextAlarm(now: ZonedDateTime): Decoration {
        val next = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return Decoration(ticking = true)
        val time = Instant.ofEpochMilli(next.triggerTime).atZone(now.zone)
        return Decoration(text(ALARM_SYMBOL), text(time.format(timeFormat())), ticking = true)
    }

    /** Today's moon emoji and the days left until the full moon, from the mean length of a lunar cycle. */
    private fun moon(now: ZonedDateTime): Decoration {
        val days = Duration.between(KNOWN_NEW_MOON, now.toInstant()).toMillis() / MS_PER_DAY
        val age = ((days % SYNODIC_MONTH_DAYS) + SYNODIC_MONTH_DAYS) % SYNODIC_MONTH_DAYS
        val phase = (age / SYNODIC_MONTH_DAYS * MOON_PHASES.size + HALF).toInt() % MOON_PHASES.size
        val daysToFull = ((SYNODIC_MONTH_DAYS / 2 - age + SYNODIC_MONTH_DAYS) % SYNODIC_MONTH_DAYS).roundToInt()
        val label = if (daysToFull == 0) context.getString(R.string.moon_full) else context.getString(R.string.days_short, daysToFull)
        return Decoration(text(MOON_PHASES[phase]), text(label), ticking = true)
    }

    /** Days until Saturday, or a celebration on the weekend itself. */
    private fun weekend(now: ZonedDateTime): Decoration {
        val today = now.dayOfWeek
        if (today == DayOfWeek.SATURDAY || today == DayOfWeek.SUNDAY) {
            return Decoration(text(PARTY_SYMBOL), text(context.getString(R.string.weekend_now)), ticking = true)
        }
        val days = DayOfWeek.SATURDAY.value - today.value
        return Decoration(text(BEACH_SYMBOL), text(context.getString(R.string.days_short, days)), ticking = true)
    }

    /**
     * Picks a seasonal effect by date: fireworks for New Year and both Eids, a crescent for Ramadan, snow in
     * December, hearts before Valentine's Day and a ghost before Halloween. Other days show the eyes.
     * Islamic dates use the Umm al-Qura calendar, which can differ from local moon sighting by a day.
     */
    private fun holiday(now: ZonedDateTime): Decoration {
        val islamic = IslamicCalendar().apply {
            calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
            timeInMillis = now.toInstant().toEpochMilli()
        }
        val islamicMonth = islamic.get(Calendar.MONTH)
        val islamicDay = islamic.get(Calendar.DAY_OF_MONTH)
        val isEid = (islamicMonth == IslamicCalendar.SHAWWAL && islamicDay <= EID_AL_FITR_DAYS) ||
            (islamicMonth == IslamicCalendar.DHU_AL_HIJJAH && islamicDay in EID_AL_ADHA_DAYS)
        val isNewYear = (now.month == Month.DECEMBER && now.dayOfMonth == LAST_DAY_OF_DECEMBER) ||
            (now.month == Month.JANUARY && now.dayOfMonth == 1)
        val effect = when {
            isNewYear || isEid -> PillEffect.FIREWORKS
            islamicMonth == IslamicCalendar.RAMADAN -> PillEffect.CRESCENT
            now.month == Month.DECEMBER -> PillEffect.SNOW
            now.month == Month.FEBRUARY && now.dayOfMonth in VALENTINE_DAYS -> PillEffect.HEARTBEAT
            now.month == Month.OCTOBER && now.dayOfMonth >= HALLOWEEN_START_DAY -> PillEffect.GHOST
            else -> null
        }
        return if (effect != null) Decoration(effect = effect, ticking = true) else Decoration(Glance.Eye(), Glance.Eye(), ticking = true)
    }

    /** A different cute emoji pair each day, the same all day long. */
    private fun emojiOfTheDay(now: ZonedDateTime): Decoration {
        val (left, right) = EMOJI_PAIRS[(now.year * DAYS_PER_YEAR + now.dayOfYear) % EMOJI_PAIRS.size]
        return Decoration(text(left), text(right), ticking = true)
    }

    private fun text(value: CharSequence) = Glance.Text(value)

    private fun timeFormat(): DateTimeFormatter = if (DateFormat.is24HourFormat(context)) TIME_24 else TIME_12

    private companion object {
        val EFFECT_ONLY = mapOf(
            IdleWidget.NONE to PillEffect.NONE,
            IdleWidget.GLOW to PillEffect.GLOW,
            IdleWidget.ORBIT to PillEffect.ORBIT,
            IdleWidget.CHOMP to PillEffect.CHOMP,
            IdleWidget.HEARTBEAT to PillEffect.HEARTBEAT,
            IdleWidget.SLEEPY_CAT to PillEffect.SLEEPY_CAT,
            IdleWidget.PEEKABOO to PillEffect.PEEKABOO,
            IdleWidget.GHOST to PillEffect.GHOST,
            IdleWidget.BUNNY to PillEffect.BUNNY,
            IdleWidget.FIREFLIES to PillEffect.FIREFLIES,
            IdleWidget.SNOW to PillEffect.SNOW,
            IdleWidget.STARS to PillEffect.STARS,
            IdleWidget.RAIN to PillEffect.RAIN,
            IdleWidget.SKY to PillEffect.SKY,
            IdleWidget.BOUNCE to PillEffect.BOUNCE,
            IdleWidget.LAVA to PillEffect.LAVA,
            IdleWidget.RIPPLE to PillEffect.RIPPLE,
            IdleWidget.PENDULUM to PillEffect.PENDULUM,
            IdleWidget.SHIMMER to PillEffect.SHIMMER,
            IdleWidget.RAINBOW to PillEffect.RAINBOW,
            IdleWidget.FIREWORKS to PillEffect.FIREWORKS,
            IdleWidget.CRESCENT to PillEffect.CRESCENT,
        )

        val WEEKDAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")
        val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")
        val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d")
        val TIME_24: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
        val TIME_12: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm")

        const val CHARGING_SYMBOL = "⚡"
        const val BATTERY_SYMBOL = "🔋"
        const val ALARM_SYMBOL = "⏰"
        const val PARTY_SYMBOL = "🎉"
        const val BEACH_SYMBOL = "🏖"

        // Moon phase: a reference new moon and the mean synodic month.
        val KNOWN_NEW_MOON: Instant = Instant.parse("2000-01-06T18:14:00Z")
        const val SYNODIC_MONTH_DAYS = 29.530588853
        const val MS_PER_DAY = 86_400_000.0
        const val HALF = 0.5
        val MOON_PHASES = listOf("🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘")

        const val EID_AL_FITR_DAYS = 3
        val EID_AL_ADHA_DAYS = 10..13
        val VALENTINE_DAYS = 7..14
        const val HALLOWEEN_START_DAY = 25
        const val LAST_DAY_OF_DECEMBER = 31

        const val DAYS_PER_YEAR = 366
        val EMOJI_PAIRS = listOf(
            "🐱" to "🐾", "🌸" to "🌸", "⭐" to "✨", "🐶" to "🦴", "🍓" to "🍰",
            "🌈" to "☁️", "🐝" to "🌼", "🦋" to "🌷", "🍩" to "☕", "🐼" to "🎋",
            "🌙" to "⭐", "🐧" to "❄️", "🍉" to "🌞", "🦄" to "✨", "🐸" to "🍃",
            "🎈" to "🎉", "🐢" to "🌊", "🍀" to "🌟", "🐰" to "🥕", "🦊" to "🍂",
        )
    }
}
