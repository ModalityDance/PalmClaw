package com.palmclaw.cron

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.palmclaw.config.AppSession
import com.palmclaw.config.ConfigStore
import com.palmclaw.storage.AppDatabase
import com.palmclaw.storage.MessageRepository
import com.palmclaw.storage.SessionRepository
import com.palmclaw.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.absoluteValue

class CronService(
    context: Context,
    private val repository: CronRepository
) {
    var onJob: (suspend (CronJob) -> String?)?
        get() = sharedOnJob
        set(value) {
            sharedOnJob = value
        }

    var onLog: ((String) -> Unit)? = null

    private val appContext = context.applicationContext
    private val configStore = ConfigStore(appContext)
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val activeExecutions = AtomicInteger(0)
    private val messageRepository by lazy {
        MessageRepository(AppDatabase.getInstance(appContext).messageDao())
    }
    private val sessionRepository by lazy {
        val db = AppDatabase.getInstance(appContext)
        SessionRepository(db.sessionDao(), db.messageDao())
    }

    private var minEveryMsPolicy: Long = DEFAULT_MIN_EVERY_MS
    private var maxJobsPolicy: Int = DEFAULT_MAX_JOBS
    private var logEnabled: Boolean = true

    init {
        val cfg = configStore.getCronConfig()
        minEveryMsPolicy = cfg.minEveryMs.coerceIn(MIN_POLICY_EVERY_MS, MAX_POLICY_EVERY_MS)
        maxJobsPolicy = cfg.maxJobs.coerceIn(MIN_POLICY_MAX_JOBS, MAX_POLICY_MAX_JOBS)
        logEnabled = cfg.enabled
    }

    fun updatePolicy(minEveryMs: Long, maxJobs: Int, logEnabled: Boolean) {
        minEveryMsPolicy = minEveryMs.coerceIn(MIN_POLICY_EVERY_MS, MAX_POLICY_EVERY_MS)
        maxJobsPolicy = maxJobs.coerceIn(MIN_POLICY_MAX_JOBS, MAX_POLICY_MAX_JOBS)
        this.logEnabled = logEnabled
        scope.launch {
            mutex.withLock {
                if (isServiceEnabled()) {
                    armNextAlarmLocked()
                } else {
                    cancelAlarmLocked()
                }
            }
        }
        emitLog("Cron policy updated: minEveryMs=$minEveryMsPolicy, maxJobs=$maxJobsPolicy, logEnabled=$logEnabled")
    }

    fun start() {
        scope.launch {
            mutex.withLock {
                if (!isServiceEnabled()) {
                    cancelAlarmLocked()
                    emitLog("Cron start ignored: service disabled in config")
                    return@withLock
                }
                recomputeNextRunsLocked()
                armNextAlarmLocked()
                emitLog("Cron started")
            }
        }
    }

    fun stop() {
        scope.launch {
            mutex.withLock {
                cancelAlarmLocked()
            }
            emitLog("Cron stopped")
        }
    }

    fun close() {
        scope.cancel()
    }

    suspend fun listJobs(includeDisabled: Boolean = false): List<CronJob> {
        val jobs = repository.listJobs()
        val filtered = if (includeDisabled) jobs else jobs.filter { it.enabled }
        return filtered.sortedBy { it.state.nextRunAtMs ?: Long.MAX_VALUE }
    }

    suspend fun addJob(
        name: String,
        schedule: CronSchedule,
        payload: CronPayload,
        deleteAfterRun: Boolean = false
    ): CronJob {
        val currentJobs = repository.listJobs()
        if (currentJobs.size >= maxJobsPolicy) {
            throw IllegalStateException("Cron job limit reached ($maxJobsPolicy)")
        }
        validateScheduleForAdd(schedule)
        val now = nowMs()
        val job = CronJob(
            id = UUID.randomUUID().toString().take(8),
            name = name,
            enabled = true,
            schedule = schedule,
            payload = payload,
            state = CronJobState(nextRunAtMs = computeNextRun(schedule, now)),
            createdAtMs = now,
            updatedAtMs = now,
            deleteAfterRun = deleteAfterRun
        )
        repository.upsert(job)
        mutex.withLock {
            if (isServiceEnabled()) {
                armNextAlarmLocked()
            }
        }
        emitLog("Cron add job id=${job.id} name=${job.name} kind=${job.schedule.kind} next=${job.state.nextRunAtMs}")
        return job
    }

    suspend fun removeJob(jobId: String): Boolean {
        val existing = repository.getJob(jobId) ?: return false
        repository.remove(existing.id)
        mutex.withLock {
            if (isServiceEnabled()) {
                armNextAlarmLocked()
            } else {
                cancelAlarmLocked()
            }
        }
        emitLog("Cron removed job id=$jobId")
        return true
    }

    suspend fun enableJob(jobId: String, enabled: Boolean = true): CronJob? {
        val job = repository.getJob(jobId) ?: return null
        val now = nowMs()
        val updated = job.copy(
            enabled = enabled,
            state = job.state.copy(
                nextRunAtMs = if (enabled) computeNextRun(job.schedule, now) else null
            ),
            updatedAtMs = now
        )
        repository.upsert(updated)
        mutex.withLock {
            if (isServiceEnabled()) {
                armNextAlarmLocked()
            } else {
                cancelAlarmLocked()
            }
        }
        emitLog("Cron set enabled id=$jobId enabled=$enabled next=${updated.state.nextRunAtMs}")
        return updated
    }

    suspend fun runJob(jobId: String, force: Boolean = false): Boolean {
        val job = repository.getJob(jobId) ?: return false
        if (!force && !job.enabled) return false
        executeJob(job, checkDue = false)
        mutex.withLock {
            if (isServiceEnabled()) {
                armNextAlarmLocked()
            } else {
                cancelAlarmLocked()
            }
        }
        emitLog("Cron run-now id=$jobId force=$force")
        return true
    }

    suspend fun status(): Map<String, Any?> {
        val jobs = repository.listJobs()
        val enabled = isServiceEnabled()
        val nextWake = jobs.asSequence()
            .filter { it.enabled }
            .mapNotNull { it.state.nextRunAtMs }
            .minOrNull()
        return mapOf(
            "enabled" to enabled,
            "jobs" to jobs.size,
            "next_wake_at_ms" to (if (enabled) nextWake else null),
            "min_every_ms" to minEveryMsPolicy,
            "max_jobs" to maxJobsPolicy
        )
    }

    fun isExecutingJob(): Boolean = activeExecutions.get() > 0

    suspend fun processDueJobs(triggerAtMs: Long = nowMs()) {
        if (!isServiceEnabled()) {
            mutex.withLock { cancelAlarmLocked() }
            emitLog("Cron alarm ignored: service disabled")
            return
        }

        val dueJobs = mutex.withLock {
            repository.listJobs().filter {
                it.enabled &&
                    it.state.nextRunAtMs != null &&
                    triggerAtMs >= it.state.nextRunAtMs
            }
        }

        if (dueJobs.isNotEmpty()) {
            for (job in dueJobs) {
                runCatching { executeJob(job, checkDue = true) }
                    .onFailure { t ->
                        emitLog("Cron due execution failed id=${job.id}: ${t.message}", t)
                    }
            }
        }

        mutex.withLock {
            if (!isServiceEnabled()) {
                cancelAlarmLocked()
            } else {
                armNextAlarmLocked()
            }
        }
    }

    suspend fun onSystemResync(triggerAtMs: Long = nowMs()) {
        if (!isServiceEnabled()) {
            mutex.withLock { cancelAlarmLocked() }
            emitLog("Cron resync ignored: service disabled")
            return
        }

        mutex.withLock { recomputeNextRunsLocked() }
        processDueJobs(triggerAtMs)
        emitLog("Cron resynced with system time")
    }

    private suspend fun executeJob(job: CronJob, checkDue: Boolean) {
        val fresh = repository.getJob(job.id) ?: return
        if (!fresh.enabled && checkDue) return
        if (checkDue) {
            val dueAt = fresh.state.nextRunAtMs ?: return
            if (nowMs() < dueAt) return
        }

        val startedAt = nowMs()
        var status = CronStatus.OK
        var error: String? = null
        var usedFallback = false

        activeExecutions.incrementAndGet()
        try {
            val callback = onJob
            if (callback != null) {
                try {
                    callback.invoke(fresh)
                    emitLog("Cron executed job=${fresh.id} name=${fresh.name}")
                } catch (t: Throwable) {
                    status = CronStatus.ERROR
                    error = t.message ?: t.javaClass.simpleName
                    emitLog("Cron job callback failed id=${fresh.id} error=$error", t)
                    runCatching {
                        postFallbackReminder(fresh, "(auto handling failed; fallback reminder used)")
                        usedFallback = true
                    }.onFailure { fallbackErr ->
                        emitLog("Cron fallback reminder failed id=${fresh.id}: ${fallbackErr.message}", fallbackErr)
                    }
                }
            } else {
                runCatching { postFallbackReminder(fresh, null) }
                    .onSuccess {
                        usedFallback = true
                        emitLog("Cron executed fallback reminder job=${fresh.id} name=${fresh.name}")
                    }
                    .onFailure { t ->
                        status = CronStatus.ERROR
                        error = t.message ?: t.javaClass.simpleName
                        emitLog("Cron fallback reminder failed id=${fresh.id} error=$error", t)
                    }
            }
        } finally {
            activeExecutions.decrementAndGet()
        }

        if (fresh.schedule.kind == CronKinds.AT) {
            if (fresh.deleteAfterRun) {
                repository.remove(fresh.id)
                emitLog("Cron one-shot deleted id=${fresh.id}")
                return
            }
            repository.upsert(
                fresh.copy(
                    enabled = false,
                    state = fresh.state.copy(
                        nextRunAtMs = null,
                        lastRunAtMs = startedAt,
                        lastStatus = status,
                        lastError = error
                    ),
                    updatedAtMs = nowMs()
                )
            )
            return
        }

        val next = computeNextRun(fresh.schedule, nowMs())
        repository.upsert(
            fresh.copy(
                state = fresh.state.copy(
                    nextRunAtMs = next,
                    lastRunAtMs = startedAt,
                    lastStatus = status,
                    lastError = error
                ),
                updatedAtMs = nowMs()
            )
        )
        emitLog(
            "Cron next scheduled id=${fresh.id} next=$next status=$status fallback=$usedFallback"
        )
    }

    private suspend fun postFallbackReminder(job: CronJob, suffix: String?) {
        val reminderText = job.payload.message.trim().ifBlank { job.name }
        postSystemNotification(reminderText)

        val targetSessionId = job.payload.sessionId?.trim().orEmpty().ifBlank { AppSession.SHARED_SESSION_ID }
        sessionRepository.ensureSessionExists(targetSessionId, AppSession.SHARED_SESSION_TITLE)
        sessionRepository.touch(targetSessionId)
        val content = buildString {
            append("Scheduled reminder: ")
            append(reminderText)
            if (!suffix.isNullOrBlank()) {
                append('\n')
                append(suffix)
            }
        }
        messageRepository.appendAssistantMessage(
            sessionId = targetSessionId,
            content = content
        )
        sessionRepository.touch(targetSessionId)
    }

    private fun postSystemNotification(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                emitLog("Skip notification: POST_NOTIFICATIONS not granted")
                return
            }
        }

        ensureReminderChannel()
        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_OPEN_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PalmClaw")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .build()

        NotificationManagerCompat.from(appContext).notify(
            (message.hashCode() xor System.currentTimeMillis().toInt()).absoluteValue,
            notification
        )
    }

    private fun ensureReminderChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(REMINDER_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            REMINDER_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Scheduled reminders from PalmClaw cron"
        }
        nm.createNotificationChannel(channel)
    }

    private suspend fun recomputeNextRunsLocked() {
        val now = nowMs()
        repository.listJobs().forEach { job ->
            if (!job.enabled) return@forEach
            val next = when (job.schedule.kind) {
                CronKinds.AT -> job.state.nextRunAtMs ?: computeNextRun(job.schedule, now)
                else -> computeNextRun(job.schedule, now)
            }
            repository.upsert(
                job.copy(
                    state = job.state.copy(nextRunAtMs = next),
                    updatedAtMs = now
                )
            )
        }
    }

    private suspend fun armNextAlarmLocked() {
        val pi = alarmPendingIntent()
        alarmManager.cancel(pi)

        if (!isServiceEnabled()) {
            return
        }

        val nextWake = repository.listJobs()
            .asSequence()
            .filter { it.enabled }
            .mapNotNull { it.state.nextRunAtMs }
            .minOrNull()
            ?: return

        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextWake,
                        pi
                    )
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT -> {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        nextWake,
                        pi
                    )
                }

                else -> {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        nextWake,
                        pi
                    )
                }
            }
        } catch (se: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextWake,
                    pi
                )
                emitLog("Exact alarm denied; fallback to inexact alarm. nextWake=$nextWake")
            } else {
                throw se
            }
        }

        emitLog("Cron alarm armed at=$nextWake")
    }

    private fun cancelAlarmLocked() {
        alarmManager.cancel(alarmPendingIntent())
    }

    private fun alarmPendingIntent(): PendingIntent {
        val intent = Intent(appContext, CronAlarmReceiver::class.java).apply {
            action = ACTION_ALARM_WAKE
            setPackage(appContext.packageName)
        }
        return PendingIntent.getBroadcast(
            appContext,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun computeNextRun(schedule: CronSchedule, nowMs: Long): Long? {
        return when (schedule.kind) {
            CronKinds.AT -> schedule.atMs?.takeIf { it > nowMs }
            CronKinds.EVERY -> {
                val every = schedule.everyMs ?: return null
                if (every <= 0) null else nowMs + every
            }

            CronKinds.CRON -> {
                val expr = schedule.expr ?: return null
                val tz = schedule.tz?.takeIf { it.isNotBlank() } ?: TimeZone.getDefault().id
                computeNextCronRun(expr, tz, nowMs)
            }

            else -> null
        }
    }

    private fun computeNextCronRun(expr: String, tz: String, nowMs: Long): Long? {
        val parsed = parseCron(expr) ?: return null
        val tzObj = TimeZone.getTimeZone(tz)
        val cal = Calendar.getInstance(tzObj)
        cal.timeInMillis = nowMs + 60_000
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        repeat(MAX_SCAN_MINUTES) {
            if (matchesCron(parsed, cal)) {
                return cal.timeInMillis
            }
            cal.add(Calendar.MINUTE, 1)
        }
        return null
    }

    private data class ParsedCron(
        val minutes: BooleanArray,
        val hours: BooleanArray,
        val days: BooleanArray,
        val months: BooleanArray,
        val weekdays: BooleanArray
    )

    private fun parseCron(expr: String): ParsedCron? {
        val parts = expr.trim().split(Regex("\\s+"))
        if (parts.size != 5) return null
        val min = parseField(parts[0], 0, 59) ?: return null
        val hour = parseField(parts[1], 0, 23) ?: return null
        val day = parseField(parts[2], 1, 31) ?: return null
        val month = parseField(parts[3], 1, 12) ?: return null
        val weekday = parseField(parts[4], 0, 7) ?: return null
        return ParsedCron(min, hour, day, month, weekday)
    }

    private fun parseField(token: String, min: Int, max: Int): BooleanArray? {
        val result = BooleanArray(max + 1)
        fun mark(value: Int) {
            if (value in min..max) result[value] = true
        }

        val chunks = token.split(",")
        for (chunk in chunks) {
            if (chunk == "*") {
                for (v in min..max) mark(v)
                continue
            }

            val stepSplit = chunk.split("/")
            val base = stepSplit[0]
            val step = if (stepSplit.size > 1) stepSplit[1].toIntOrNull() ?: return null else 1
            if (step <= 0) return null

            if (base == "*") {
                var v = min
                while (v <= max) {
                    mark(v)
                    v += step
                }
                continue
            }

            if (base.contains("-")) {
                val range = base.split("-")
                if (range.size != 2) return null
                val start = range[0].toIntOrNull() ?: return null
                val end = range[1].toIntOrNull() ?: return null
                if (start > end) return null
                var v = start
                while (v <= end) {
                    mark(v)
                    v += step
                }
                continue
            }

            val single = base.toIntOrNull() ?: return null
            mark(single)
        }

        return if (result.any { it }) result else null
    }

    private fun matchesCron(parsed: ParsedCron, cal: Calendar): Boolean {
        val minute = cal.get(Calendar.MINUTE)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val month = cal.get(Calendar.MONTH) + 1
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) // 1..7, Sunday=1
        val cronDow = (dayOfWeek + 6) % 7 // Sunday=0
        val dowMatch = parsed.weekdays.getOrNull(cronDow) == true ||
            (cronDow == 0 && parsed.weekdays.getOrNull(7) == true)

        return parsed.minutes.getOrNull(minute) == true &&
            parsed.hours.getOrNull(hour) == true &&
            parsed.days.getOrNull(day) == true &&
            parsed.months.getOrNull(month) == true &&
            dowMatch
    }

    private fun validateScheduleForAdd(schedule: CronSchedule) {
        if (schedule.tz != null && schedule.kind != CronKinds.CRON) {
            throw IllegalArgumentException("tz can only be used with cron schedules")
        }
        when (schedule.kind) {
            CronKinds.AT -> {
                if (schedule.atMs == null) throw IllegalArgumentException("at schedule requires atMs")
            }

            CronKinds.EVERY -> {
                val every = schedule.everyMs
                    ?: throw IllegalArgumentException("every schedule requires everyMs")
                if (every <= 0) throw IllegalArgumentException("everyMs must be > 0")
                if (every < minEveryMsPolicy) {
                    throw IllegalArgumentException("everyMs must be >= $minEveryMsPolicy")
                }
            }

            CronKinds.CRON -> {
                val expr = schedule.expr ?: throw IllegalArgumentException("cron schedule requires expr")
                if (parseCron(expr) == null) throw IllegalArgumentException("invalid cron expression")
                val tz = schedule.tz
                if (!tz.isNullOrBlank() && !TimeZone.getAvailableIDs().contains(tz)) {
                    throw IllegalArgumentException("unknown timezone '$tz'")
                }
            }

            else -> throw IllegalArgumentException("unknown schedule kind '${schedule.kind}'")
        }
    }

    private fun isServiceEnabled(): Boolean = configStore.getCronConfig().enabled

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun emitLog(message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.d(TAG, message)
        } else {
            Log.e(TAG, message, throwable)
        }
        if (logEnabled) {
            onLog?.invoke(message)
        }
    }

    companion object {
        private const val TAG = "CronService"
        private const val MAX_SCAN_MINUTES = 60 * 24 * 370 // roughly 1 year
        private const val DEFAULT_MIN_EVERY_MS = 60_000L
        private const val DEFAULT_MAX_JOBS = 50
        private const val MIN_POLICY_EVERY_MS = 1_000L
        private const val MAX_POLICY_EVERY_MS = 86_400_000L
        private const val MIN_POLICY_MAX_JOBS = 1
        private const val MAX_POLICY_MAX_JOBS = 500

        const val ACTION_ALARM_WAKE = "com.palmclaw.action.CRON_ALARM_WAKE"

        private const val ALARM_REQUEST_CODE = 91001
        private const val NOTIFICATION_OPEN_REQUEST_CODE = 91002
        private const val REMINDER_CHANNEL_ID = "palmclaw_cron_reminder"
        private const val REMINDER_CHANNEL_NAME = "Scheduled reminders"

        @Volatile
        private var sharedOnJob: (suspend (CronJob) -> String?)? = null
    }
}
