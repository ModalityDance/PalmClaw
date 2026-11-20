package com.palmclaw.cron

import kotlinx.coroutines.CancellationException

/** Retains the identity and durable-write boundary when scheduler reconciliation fails. */
internal class CronMutationException(
    val jobId: String,
    val committed: Boolean,
    cause: Exception
) : Exception("Cron operation could not complete its follow-up steps.", cause)

internal suspend fun <T> commitCronChange(
    jobId: String,
    persist: suspend () -> T,
    reconcile: suspend () -> Unit
): T {
    var committed = false
    return try {
        val value = persist()
        committed = true
        reconcile()
        value
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw CronMutationException(jobId, committed, error)
    }
}
