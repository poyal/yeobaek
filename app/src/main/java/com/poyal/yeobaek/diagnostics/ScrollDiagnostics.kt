package com.poyal.yeobaek.diagnostics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.poyal.yeobaek.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/** Local, rotating TXT diagnostics. No book names, URIs, text, or network transmission. */
class ScrollDiagnostics(context: Context) {
    private sealed interface Command {
        data class Record(val wallTime: Long, val uptime: Long, val event: String, val detail: String) : Command
        data class Export(val output: OutputStream, val done: CompletableDeferred<Unit>) : Command
    }
    private val files = ScrollLogFiles(java.io.File(context.noBackupFilesDir, "txt-scroll-diagnostics"))
    private val queue = Channel<Command>(128)
    private val dropped = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            var writeFailures = 0
            for (command in queue) when (command) {
                is Command.Record -> try {
                    val time = Instant.ofEpochMilli(command.wallTime).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX"))
                    files.append("$time uptime=${command.uptime} ${command.event} ${command.detail.replace('\n', ' ').replace('\r', ' ')}")
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { writeFailures++ }
                is Command.Export -> try {
                    command.output.write(("Yeobaek TXT scroll diagnostics\n" +
                        "Local time includes UTC offset; uptime is milliseconds since boot.\n" +
                        "Retains up to 128 MiB; oldest records rotate out. raw_touch_tail may repeat earlier samples.\n" +
                        "droppedRecords=${dropped.get()} writeFailures=$writeFailures\n\n").toByteArray())
                    files.copyTo(command.output)
                    command.done.complete(Unit)
                } catch (e: Exception) { command.done.completeExceptionally(e) }
            }
        }
        record("process", "app=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} type=${BuildConfig.BUILD_TYPE} " +
            "manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} build=${Build.ID}")
    }

    fun record(event: String, detail: String, uptime: Long = SystemClock.uptimeMillis()) {
        val wallTime = System.currentTimeMillis() + uptime - SystemClock.uptimeMillis()
        if (!queue.trySend(Command.Record(wallTime, uptime, event, detail.take(12000))).isSuccess) dropped.incrementAndGet()
    }

    suspend fun export(output: OutputStream) = withContext(NonCancellable) {
        val done = CompletableDeferred<Unit>()
        queue.send(Command.Export(output, done))
        done.await()
    }
}
