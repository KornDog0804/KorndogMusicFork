package expo.modules.noutubeview

import android.os.SystemClock

typealias LogFn = (String) -> Unit
typealias SleepTimerEventFn = (Map<String, Any?>) -> Unit

private const val SLEEP_TIMER_REASON_SET = "set"
private const val SLEEP_TIMER_REASON_CLEAR = "clear"
private const val SLEEP_TIMER_REASON_EXPIRED = "expired"

class NouController {
  internal var service: NouService? = null
  internal var logFn: LogFn? = null
  internal var sleepTimerEventFn: SleepTimerEventFn? = null

  private var pendingSleepTimerDeadlineMs: Long? = null
  private var hasPendingSleepTimerChange = false

  fun log(msg: String) {
    logFn?.invoke(msg)
  }

  fun setSleepTimer(durationMs: Long) {
    val nextDeadlineMs = SystemClock.elapsedRealtime() + durationMs
    val currentService = service

    if (currentService != null) {
      currentService.setSleepTimerDeadline(nextDeadlineMs)
      emitSleepTimerSet(currentService.getSleepTimerRemainingMs())
      return
    }

    pendingSleepTimerDeadlineMs = nextDeadlineMs
    hasPendingSleepTimerChange = true
    emitSleepTimerSet(maxOf(0L, nextDeadlineMs - SystemClock.elapsedRealtime()))
  }

  fun clearSleepTimer() {
    val currentService = service

    if (currentService != null) {
      currentService.clearSleepTimer()
      emitSleepTimerCleared()
      return
    }

    pendingSleepTimerDeadlineMs = null
    hasPendingSleepTimerChange = true
    emitSleepTimerCleared()
  }

  fun getSleepTimerRemainingMs(): Long? {
    val currentService = service

    if (currentService != null) {
      return currentService.getSleepTimerRemainingMs()
    }

    val pendingDeadlineMs = pendingSleepTimerDeadlineMs ?: return null
    return maxOf(0L, pendingDeadlineMs - SystemClock.elapsedRealtime())
  }

  fun applyPendingSleepTimer() {
    if (!hasPendingSleepTimerChange) return

    val currentService = service ?: return

    hasPendingSleepTimerChange = false

    val pendingDeadlineMs = pendingSleepTimerDeadlineMs
    pendingSleepTimerDeadlineMs = null

    if (pendingDeadlineMs != null) {
      currentService.setSleepTimerDeadline(pendingDeadlineMs)
      emitSleepTimerSet(currentService.getSleepTimerRemainingMs())
    } else {
      currentService.clearSleepTimer()
      emitSleepTimerCleared()
    }
  }

  fun emitSleepTimer(remainingMs: Long?, reason: String) {
    sleepTimerEventFn?.invoke(
      mapOf(
        "remainingMs" to remainingMs,
        "reason" to reason
      )
    )
  }

  fun emitSleepTimerSet(remainingMs: Long?) {
    emitSleepTimer(remainingMs, SLEEP_TIMER_REASON_SET)
  }

  fun emitSleepTimerCleared() {
    emitSleepTimer(null, SLEEP_TIMER_REASON_CLEAR)
  }

  fun emitSleepTimerExpired() {
    emitSleepTimer(null, SLEEP_TIMER_REASON_EXPIRED)
  }

  fun exit() {
    service?.exit()
  }
}

val nouController = NouController()
