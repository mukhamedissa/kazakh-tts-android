package io.github.mukhamedissa.kazakhtts.logger

import android.util.Log

/**
 * Logging sink for the KazakhTts library.
 *
 * Set [io.github.mukhamedissa.kazakhtts.KazakhTts.Companion.logger] to [NONE] to suppress all output, or supply your own
 * implementation to route logs to Timber or any other sink.
 *
 * Example — Timber integration:
 * ```kotlin
 * KazakhTts.logger = Logger { priority, tag, message, throwable ->
 *     Timber.tag(tag).log(priority, throwable, message)
 * }
 * ```
 */
fun interface Logger {
    fun log(priority: Int, tag: String, message: String, throwable: Throwable?)

    companion object {
        val ANDROID: Logger = Logger { priority, tag, message, throwable ->
            if (throwable != null) {
                Log.println(priority, tag, "$message\n${Log.getStackTraceString(throwable)}")
            } else {
                Log.println(priority, tag, message)
            }
        }

        val NONE: Logger = Logger { _, _, _, _ -> }
    }
}

fun Logger.d(tag: String, message: String) = log(Log.DEBUG, tag, message, null)
fun Logger.w(tag: String, message: String, throwable: Throwable? = null) = log(Log.WARN, tag, message, throwable)
fun Logger.e(tag: String, message: String, throwable: Throwable? = null) = log(Log.ERROR, tag, message, throwable)
