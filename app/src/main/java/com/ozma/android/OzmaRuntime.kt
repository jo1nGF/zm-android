package com.ozma.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process as AndroidProcess
import android.view.SurfaceHolder
import android.view.InputDevice
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.ZipInputStream

/**
 * Runs OZMA's unmodified aarch64-glibc `bricktracker` as a child process through the bundled glibc loader and bridges
 * its (shimmed) SDL video / audio / input to Android. See shim/sdl_shim.c for the wire format.
 */
class OzmaRuntime(private val ctx: Context, private val onExit: (Int, String) -> Unit) {
    companion object {
        const val MAX_W = 1920; const val MAX_H = 1080; const val HDR = 64
        // SDL_GameControllerButton / axis ids (what the shim expects)
        const val BTN_A = 0; const val BTN_B = 1; const val BTN_X = 2; const val BTN_Y = 3
        const val BTN_BACK = 4; const val BTN_GUIDE = 5; const val BTN_START = 6
        const val BTN_LSTICK = 7; const val BTN_RSTICK = 8; const val BTN_LSHOULDER = 9; const val BTN_RSHOULDER = 10
        const val BTN_UP = 11; const val BTN_DOWN = 12; const val BTN_LEFT = 13; const val BTN_RIGHT = 14
        const val AX_LX = 0; const val AX_LY = 1; const val AX_RX = 2; const val AX_RY = 3; const val AX_LT = 4; const val AX_RT = 5
    }

    private val home = File(ctx.filesDir, "ozma")
    private val ipc = File(ctx.filesDir, "ipc")
    val dataDir: File = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).let { File(it, "OZMA") }
    val logFile = File(ctx.filesDir, "ozma.log")
    val traceFile = File(ctx.filesDir, "ozma-trace.log")
    private val traceLock = Any()
    @Volatile private var currentStage = "idle"
    @Volatile private var lastTraceEvent = "idle"
    private val connectedIpc = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var lastExitCode: Int? = null
    @Volatile private var lastPid: Long? = null
    @Volatile private var logcatExcerpt = ""
    private val ldDebugBase = File(ctx.filesDir, "ozma-ld-debug")
    private data class ProbeResult(val name: String, val exitCode: Int, val timedOut: Boolean, val output: String)

    private fun trace(stage: String, detail: String = "", phase: Boolean = false) {
        lastTraceEvent = stage
        if (phase) currentStage = stage
        val safeDetail = detail.replace('\n', ' ').replace('\r', ' ')
        val line = "${System.currentTimeMillis()} [${stage}] ${safeDetail}\n"
        synchronized(traceLock) {
            try {
                traceFile.parentFile?.mkdirs()
                traceFile.appendText(line, Charsets.UTF_8)
            } catch (_: Throwable) { }
        }
    }

    private fun tailFile(file: File, count: Int): String = try {
        if (!file.exists()) "" else file.readLines().takeLast(count).joinToString("\n")
    } catch (_: Throwable) { "" }

    @Volatile private var process: Process? = null
    @Volatile private var inputOut: OutputStream? = null
    @Volatile private var surface: SurfaceHolder? = null
    @Volatile private var stopping = false
    @Volatile var videoSize = Pair(1024, 768); private set
    private val servers = mutableListOf<LocalServerSocket>()

    fun attachSurface(h: SurfaceHolder?) { surface = h }

    // ---------------------------------------------------------------- start / stop
    fun start() {
        stopping = false
        lastExitCode = null
        lastPid = null
        logcatExcerpt = ""
        connectedIpc.clear()
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("ozma-ld-debug.") }?.forEach { it.delete() }
        synchronized(traceLock) {
            try { traceFile.parentFile?.mkdirs(); traceFile.writeText("", Charsets.UTF_8) } catch (_: Throwable) { }
        }
        trace("START_REQUEST", "runtime=${home.absolutePath} data=${dataDir.absolutePath}", phase = true)
        Thread({
            try {
                startInternal()
            } catch (t: Throwable) {
                trace("START_FAILED", android.util.Log.getStackTraceString(t), phase = true)
                onExit(-1, "start failed: $t\nTrace: ${tailFile(traceFile, 80)}")
            }
        }, "ozma-start").start()
    }

    private fun startInternal() {
        trace("EXTRACTION_BEGIN", "runtime assets=runtime.zip/runtime.id", phase = true)
        extractIfNeeded()
        val loader = File(home, "lib/ld-linux-aarch64.so.1")
        val binary = File(home, "bin/bricktracker")
        val shim = File(home, "lib/libSDL2-2.0.so.0")
        trace("EXTRACTION_READY", "loader=${loader.exists()}/${loader.length()} executable=${loader.canExecute()}; binary=${binary.exists()}/${binary.length()} executable=${binary.canExecute()}; shim=${shim.exists()}/${shim.length()} executable=${shim.canExecute()}; stamp=${File(home, ".id").let { if (it.exists()) it.readText() else "<missing>" }}", phase = true)

        // Run small, ordinary glibc programs under the same inherited Android seccomp filter
        // before attempting the unmodified application. Stop at the first failing layer.
        val loaderPath = File(home, "lib/ld-linux-aarch64.so.1").path
        val libDir = File(home, "lib")
        val probes = listOf(
            "glibc-basic" to File(home, "diagnostics/glibc-basic"),\n            "glibc-pthread-link-only" to File(home, "diagnostics/glibc-pthread-link-only"),
            "glibc-pthread" to File(home, "diagnostics/glibc-pthread"),
            "glibc-sdl-dlopen" to File(home, "diagnostics/glibc-sdl-dlopen")
        )
        for ((name, probeFile) in probes) {
            val result = runGlibcProbe(name, probeFile, loaderPath, libDir)
            if (result.exitCode != 0) {
                lastExitCode = result.exitCode
                trace("GLIBC_PREFLIGHT_FAILED", "probe=$name exitCode=${result.exitCode} timedOut=${result.timedOut}", phase = true)
                onExit(result.exitCode, "Preflight probe failed: $name (exit=${result.exitCode}, timeout=${result.timedOut})\n${result.output}\nTrace: ${tailFile(traceFile, 120)}")
                return
            }
        }
        trace("GLIBC_PREFLIGHT_PASSED", "basic startup, pthread and SDL dynamic load succeeded", phase = true)

        ipc.deleteRecursively(); ipc.mkdirs(); dataDir.mkdirs()
        trace("IPC_DIRECTORY_READY", "ipc=${ipc.absolutePath} data=${dataDir.absolutePath}", phase = true)
        // shared frames file
        val shmFile = File(ipc, "frames.shm")
        val raf = RandomAccessFile(shmFile, "rw")
        raf.setLength((HDR + 2L * MAX_W * MAX_H * 4))
        val shm = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
        trace("SHARED_MEMORY_READY", "path=${shmFile.absolutePath} bytes=${raf.length()}", phase = true)

        val vs = server("video.sock")
        val asrv = server("audio.sock")
        val isrv = server("input.sock")

        val lib = File(home, "lib")
        val bin = File(home, "bin/bricktracker")
        val command = listOf(loaderPath, "--library-path", lib.path, bin.path, "--fullscreen")
        val pidFile = File(ipc, "bricktracker.pid")
        pidFile.delete()
        // Process.pid() is not exposed by Android's java.lang.Process API. A shell writes $$,
        // then exec replaces itself with the loader so the child PID remains stable.
        val shellLauncher = "echo \$\$ > \"\$1\"; shift; exec \"\$@\""
        val launchCommand = listOf("/system/bin/sh", "-c", shellLauncher, "ozma-launcher", pidFile.path) + command
        val pb = ProcessBuilder(launchCommand)
        pb.directory(dataDir)
        val env = pb.environment()
        env.remove("LD_LIBRARY_PATH"); env.remove("LD_PRELOAD")        // Android's bionic paths must not leak into glibc
        env["OZMA_IPC"] = ipc.path; env["OZMA_W"] = "1024"; env["OZMA_H"] = "768"
        env["HOME"] = dataDir.path; env["BT_APP_DIR"] = home.path; env["BT_DATA"] = dataDir.path
        env["TMPDIR"] = ctx.cacheDir.path
        // Temporary loader diagnostics: the glibc loader appends its PID to LD_DEBUG_OUTPUT.
        env["LD_DEBUG"] = "libs,versions"
        env["LD_DEBUG_OUTPUT"] = ldDebugBase.absolutePath
        trace("GLIBC_LD_DEBUG_ENABLED", "categories=libs,versions outputPrefix=${ldDebugBase.absolutePath}")
        // what platform/detect.sh exports for the 'safe' profile (no sysfs, no codec, no USB gadget, ...)
        mapOf("BT_ALSA_DIRECT" to "0", "BT_STICK_AS_DPAD" to "1", "BT_FN_KEYS" to "0", "BT_DEVICE_WORD" to "CONSOLE",
            "BT_STUDIO" to "0", "BT_STUDIO_CLOCKS" to "0", "BT_USB_HOSTFIX" to "0", "BT_USB_AVAIL" to "0",
            "BT_SPEAKER_CTL" to "0", "BT_MIC_MIXER" to "0", "BT_PAUSE_SERVICES" to "", "BT_IDLE_INHIBIT" to "none",
            "BT_POWER_KEY" to "os", "BT_UPDATE" to "notify", "BT_REPORT" to "0").forEach { (k, v) -> env[k] = v }
        logFile.parentFile?.mkdirs()
        logFile.delete()
        pb.redirectErrorStream(true)
        pb.redirectOutput(logFile)
        trace("PROCESS_LAUNCH_BEGIN", "cwd=${dataDir.absolutePath}; command=${command.joinToString(" ")}; launcher=/system/bin/sh exec; pidFile=${pidFile.absolutePath}; OZMA_IPC=${ipc.absolutePath}; HOME=${dataDir.absolutePath}; BT_APP_DIR=${home.absolutePath}; TMPDIR=${ctx.cacheDir.absolutePath}; stdout+stderr=${logFile.absolutePath}", phase = true)
        val p = pb.start()
        process = p
        lastPid = readPidFile(pidFile)
        trace("PROCESS_STARTED", "pid=${lastPid ?: "<unavailable>"} process=${p}; pidFile=${pidFile.absolutePath}; stdoutLog=${logFile.absolutePath}", phase = true)

        accept(vs, "video") { videoLoop(it, shm) }
        accept(asrv, "audio") { audioLoop(it) }
        accept(isrv, "input") { inputOut = it.outputStream; trace("IPC_INPUT_READY", "input socket accepted") }
        trace("IPC_ACCEPT_THREADS_STARTED", "video/audio/input acceptors launched")

        Thread({
            try {
                val rc = p.waitFor()
                lastExitCode = rc
                val exitedPid = lastPid
                val channels = synchronized(connectedIpc) { connectedIpc.toList().sorted() }
                val exitStage = if (channels.isEmpty()) "PROCESS_EXIT_BEFORE_IPC" else "PROCESS_EXIT_AFTER_IPC"
                trace(exitStage, "pid=${exitedPid ?: "<unavailable>"} exitCode=$rc connectedIpc=${channels.ifEmpty { listOf("<none>") }.joinToString()}; stdoutBytes=${if (logFile.exists()) logFile.length() else 0}; shellSignalCandidate=${if (rc in 129..192) rc - 128 else "none"} (heuristic only)", phase = true)
                logcatExcerpt = exitedPid?.let { collectLogcat(it) } ?: "PID unavailable; could not correlate app-level logcat with the child process."
                trace("LOGCAT_CAPTURE_COMPLETE", logcatExcerpt)
                val combined = buildString {
                    append(tailLog())
                    if (isNotEmpty()) append("\n")
                    append("Exit code: $rc; PID: ${exitedPid ?: "<unavailable>"}\n")
                    append("Process trace: ${traceFile.absolutePath}\n")
                    append("Logcat excerpt:\n")
                    append(logcatExcerpt)
                }
                if (!stopping) onExit(rc, combined)
            } catch (t: Throwable) {
                trace("WAIT_FAILED", android.util.Log.getStackTraceString(t))
                if (!stopping) onExit(-1, "wait failed: $t\nTrace: ${tailFile(traceFile, 100)}")
            }
        }, "ozma-wait").start()
    }

    private fun runGlibcProbe(name: String, binary: File, loaderPath: String, libDir: File): ProbeResult {
        if (!binary.isFile || !binary.canExecute()) {
            val message = "probe binary missing or not executable: ${binary.absolutePath}"
            trace("GLIBC_PROBE_MISSING", "name=$name path=${binary.absolutePath}")
            return ProbeResult(name, 126, false, message)
        }
        val outputFile = File(ctx.filesDir, "ozma-probe-$name.log")
        val debugPrefix = File(ctx.filesDir, "ozma-probe-$name-ld")
        outputFile.delete()
        ctx.filesDir.listFiles()?.filter { it.name.startsWith(debugPrefix.name + ".") }?.forEach { it.delete() }
        val command = listOf(loaderPath, "--library-path", libDir.absolutePath, binary.absolutePath)
        val pb = ProcessBuilder(command)
        pb.directory(dataDir)
        val env = pb.environment()
        env.remove("LD_LIBRARY_PATH")
        env.remove("LD_PRELOAD")
        env["LD_DEBUG"] = "libs,versions"
        env["LD_DEBUG_OUTPUT"] = debugPrefix.absolutePath
        pb.redirectErrorStream(true)
        pb.redirectOutput(outputFile)
        trace("GLIBC_PROBE_BEGIN", "name=$name command=${command.joinToString(" ")} output=${outputFile.absolutePath}")
        return try {
            val child = pb.start()
            val finished = child.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                child.destroyForcibly()
                child.waitFor()
            }
            val rc = if (finished) child.exitValue() else 124
            val output = buildString {
                append(tailFile(outputFile, 40))
                val debug = ctx.filesDir.listFiles()?.filter { it.name.startsWith(debugPrefix.name + ".") }?.maxByOrNull { it.lastModified() }
                if (debug != null) {
                    append("\nLD_DEBUG (${debug.name}, ${debug.length()} bytes):\n")
                    append(tailFile(debug, 100))
                }
            }
            trace("GLIBC_PROBE_EXIT", "name=$name exitCode=$rc timedOut=${!finished} stdoutBytes=${if (outputFile.exists()) outputFile.length() else 0}")
            ProbeResult(name, rc, !finished, output)
        } catch (t: Throwable) {
            val message = "${t.javaClass.simpleName}: ${t.message}"
            trace("GLIBC_PROBE_LAUNCH_FAILED", "name=$name error=$message")
            ProbeResult(name, 125, false, message)
        }
    }

    private fun readPidFile(file: File): Long? {
        repeat(50) {
            val pid = try { if (file.exists()) file.readText().trim().toLongOrNull() else null } catch (_: Throwable) { null }
            if (pid != null && pid > 0) return pid
            try { Thread.sleep(10) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private fun collectLogcat(pid: Long): String {
        val dump = File(ctx.cacheDir, "ozma-logcat-dump.txt")
        return try {
            dump.delete()
            val collector = ProcessBuilder("logcat", "-b", "all", "-d", "-v", "threadtime", "-t", "1200")
                .redirectErrorStream(true)
                .redirectOutput(dump)
                .start()
            if (!collector.waitFor(3, TimeUnit.SECONDS)) {
                collector.destroyForcibly()
                "logcat collection timed out"
            } else if (!dump.exists()) {
                "logcat produced no readable output"
            } else {
                val lines = dump.readLines()
                val pidHeader = Regex("^\\S+\\s+\\S+\\s+${pid}\\s")
                val anchors = Regex("(?i)(SIGSYS|SIGSEGV|SIGABRT|seccomp|fatal signal|signal 31|debuggerd|tombstoned|avc: denied|CANNOT LINK EXECUTABLE|linker.*(bricktracker|ld-linux)|bricktracker.*(linker|fatal)|pid:\\s*${pid}\\b)")
                val hits = lines.indices.filter { i ->
                    pidHeader.containsMatchIn(lines[i]) || anchors.containsMatchIn(lines[i])
                }
                val selected = hits.flatMap { i -> (maxOf(0, i - 2)..minOf(lines.lastIndex, i + 3)) }
                    .distinct().sorted().takeLast(80)
                if (selected.isEmpty()) {
                    "No matching process/crash lines visible through app-level logcat. Android may restrict system crash records."
                } else selected.joinToString("\n") { lines[it] }
            }
        } catch (t: Throwable) {
            "logcat unavailable: $t"
        } finally {
            dump.delete()
        }
    }

    fun stop() {
        stopping = true
        send(3, 0, 0)                                  // ask for SDL_QUIT first so OZMA can save
        val p = process
        Thread({
            try {
                Thread.sleep(1500); p?.destroy()       // SIGTERM (OZMA saves on it)
                Thread.sleep(1500); p?.destroyForcibly()
            } catch (_: Throwable) {}
            servers.forEach { try { it.close() } catch (_: Throwable) {} }
        }, "ozma-stop").start()
    }

    fun tailLog(n: Int = 25): String = try { logFile.readLines().takeLast(n).joinToString("\n") } catch (_: Throwable) { "" }

    // ---------------------------------------------------------------- input
    private val inLock = Any()
    private fun send(type: Int, code: Int, value: Int) {
        val o = inputOut ?: return
        synchronized(inLock) {
            try { o.write(byteArrayOf(type.toByte(), code.toByte(), (value and 0xff).toByte(), ((value shr 8) and 0xff).toByte(), 0, 0, 0, 0)); o.flush() } catch (_: Throwable) {}
        }
    }
    fun button(code: Int, down: Boolean) = send(1, code, if (down) 1 else 0)
    fun axis(code: Int, value: Int) = send(2, code, value.coerceIn(-32768, 32767))

    // ---------------------------------------------------------------- ipc helpers
    private fun server(name: String): LocalServerSocket {
        val path = File(ipc, name)
        path.delete()
        val ls = LocalSocket()
        ls.bind(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
        val s = LocalServerSocket(ls.fileDescriptor)
        servers += s
        trace("IPC_SOCKET_BOUND", "name=$name path=${path.absolutePath}")
        return s
    }
    private fun accept(s: LocalServerSocket, name: String, body: (LocalSocket) -> Unit) {
        Thread({
            trace("IPC_ACCEPT_WAIT", "name=$name")
            try {
                val socket = s.accept()
                connectedIpc.add(name)
                trace("IPC_CONNECTED", "name=$name", phase = true)
                body(socket)
            } catch (t: Throwable) {
                trace("IPC_ACCEPT_OR_HANDLER_FAILED", "name=$name error=$t")
            }
        }, "ozma-ipc-$name").start()
    }

    // ---------------------------------------------------------------- video
    private fun videoLoop(sock: LocalSocket, shm: ByteBuffer) {
        trace("VIDEO_PIPE_ACTIVE", "video socket connected")
        val inp = sock.inputStream; val paint = Paint().apply { isFilterBitmap = false }
        var bmp: Bitmap? = null; val buf = ByteArray(64)
        while (true) {
            var n = inp.read(buf); if (n <= 0) break
            val idx = buf[n - 1].toInt() and 1                       // newest frame wins; older signals are dropped
            while (inp.available() > 0) { n = inp.read(buf); if (n <= 0) break }
            val w = shm.getInt(0); val h = shm.getInt(4)
            if (w <= 0 || h <= 0 || w > MAX_W || h > MAX_H) continue
            if (bmp == null || bmp.width != w || bmp.height != h) { bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); videoSize = Pair(w, h) }
            val off = HDR + idx * MAX_W * MAX_H * 4
            val dup = shm.duplicate().order(ByteOrder.LITTLE_ENDIAN); dup.position(off); dup.limit(off + w * h * 4)
            bmp.copyPixelsFromBuffer(dup)
            val holder = surface ?: continue
            val c: Canvas = holder.lockCanvas() ?: continue
            try {
                c.drawColor(0xFF000000.toInt())
                val cw = c.width; val ch = c.height
                val scale = minOf(cw / w.toFloat(), ch / h.toFloat())
                val dw = (w * scale).toInt(); val dh = (h * scale).toInt(); val dx = (cw - dw) / 2; val dy = (ch - dh) / 2
                c.drawBitmap(bmp, null, Rect(dx, dy, dx + dw, dy + dh), paint)
            } finally { holder.unlockCanvasAndPost(c) }
        }
    }

    // ---------------------------------------------------------------- audio
    private fun audioLoop(sock: LocalSocket) {
        trace("AUDIO_PIPE_ACTIVE", "audio socket connected")
        AndroidProcess.setThreadPriority(AndroidProcess.THREAD_PRIORITY_URGENT_AUDIO)
        val inp = DataInputStream(sock.inputStream)
        val hb = ByteArray(16); inp.readFully(hb)
        val hh = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN)
        val freq = hh.getInt(0); val fmt = hh.getInt(4); val ch = hh.getInt(8); val samples = hh.getInt(12)
        val isFloat = fmt == 0x8120; val bps = if (isFloat) 4 else 2
        val chunk = samples * ch * bps
        val mask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val enc = if (isFloat) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val min = AudioTrack.getMinBufferSize(freq, mask, enc)
        trace("AUDIO_FORMAT_RECEIVED", "freq=$freq format=0x${fmt.toString(16)} channels=$ch samples=$samples minBuffer=$min")
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(enc).setSampleRate(freq).setChannelMask(mask).build())
            .setBufferSizeInBytes(maxOf(min, chunk * 2))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
        trace("AUDIO_TRACK_CREATED", "state=${track.state} sampleRate=${track.sampleRate} buffer=${track.bufferSizeInFrames}")
        track.play()
        trace("AUDIO_TRACK_PLAYING", "playState=${track.playState}")
        val raw = ByteArray(chunk); val fl = FloatArray(samples * ch)
        try {
            while (true) {
                inp.readFully(raw)
                if (isFloat) { ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(fl); track.write(fl, 0, fl.size, AudioTrack.WRITE_BLOCKING) }
                else track.write(raw, 0, raw.size)
            }
        } finally { try { track.stop(); track.release() } catch (_: Throwable) {} }
    }

    // ---------------------------------------------------------------- extraction
    private fun extractIfNeeded() {
        val id = ctx.assets.open("runtime.id").bufferedReader().readText().trim() + "-" + BuildConfig_VERSION
        val stamp = File(home, ".id")
        if (stamp.exists() && stamp.readText() == id && File(home, "bin/bricktracker").exists()) {
            trace("EXTRACTION_SKIPPED", "matching stamp=$id")
            return
        }
        home.deleteRecursively(); home.mkdirs()
        ZipInputStream(ctx.assets.open("runtime.zip")).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(home, e.name); if (!out.canonicalPath.startsWith(home.canonicalPath)) continue   // zip-slip guard
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                if (e.name.startsWith("data/")) continue                  // data goes to the user-visible folder below
                out.outputStream().use { z.copyTo(it) }
                if (e.name.startsWith("bin/") || e.name.startsWith("lib/") || e.name.startsWith("diagnostics/")) out.setExecutable(true, false)
            }
        }
        // stock songs / samples: copy only what is missing (never overwrite the user's files)
        ZipInputStream(ctx.assets.open("runtime.zip")).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory || !e.name.startsWith("data/")) continue
                val out = File(dataDir, e.name.removePrefix("data/")); if (!out.canonicalPath.startsWith(dataDir.canonicalPath)) continue
                if (!out.exists()) { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
            }
        }
        stamp.writeText(id)
        trace("EXTRACTION_STAMP_WRITTEN", "stamp=$id runtimeFiles=${home.walkTopDown().count { it.isFile }}")
    }
    private val BuildConfig_VERSION = "3"

    private fun readLdDebugLog(): String {
        val files = ctx.filesDir.listFiles()?.filter { it.name.startsWith("ozma-ld-debug.") } ?: emptyList()
        val latest = files.maxByOrNull { it.lastModified() } ?: return "<no LD_DEBUG file created; loader may have exited before emitting diagnostics>"
        return "file=${latest.absolutePath} bytes=${latest.length()}\\n" + tailFile(latest, 180)
    }

    private fun readSmallFile(path: String): String = try {
        File(path).readText().trim().replace('\n', ' ').take(1200)
    } catch (t: Throwable) { "<unavailable: ${t.javaClass.simpleName}: ${t.message}>" }

    private fun readProcStatus(): String = try {
        File("/proc/self/status").readLines()
            .filter { it.startsWith("Name:") || it.startsWith("Pid:") || it.startsWith("PPid:") ||
                it.startsWith("Seccomp:") || it.startsWith("Seccomp_filters:") ||
                it.startsWith("NoNewPrivs:") || it.startsWith("CapEff:") }
            .joinToString("; ")
    } catch (t: Throwable) { "<unavailable: ${t.javaClass.simpleName}: ${t.message}>" }

    /** Passive diagnostics; does not execute native code or change syscall policy. */
    fun diagnosticReport(exitSummary: String = ""): String {
        fun status(f: File): String = "${f.absolutePath}: exists=${f.exists()}, readable=${f.canRead()}, executable=${f.canExecute()}, size=${if (f.exists()) f.length() else 0}"
        fun elf(f: File): String = try {
            val b = f.inputStream().use { stream ->
                val buffer = ByteArray(20)
                var offset = 0
                while (offset < buffer.size) {
                    val count = stream.read(buffer, offset, buffer.size - offset)
                    if (count <= 0) break
                    offset += count
                }
                buffer.copyOf(offset)
            }
            if (b.size < 20 || b[0] != 0x7f.toByte() || b[1] != 'E'.code.toByte() || b[2] != 'L'.code.toByte() || b[3] != 'F'.code.toByte()) "ELF header: invalid/short"
            else "ELF class=${b[4].toInt() and 255}, endian=${b[5].toInt() and 255}, machine=${(b[18].toInt() and 255) or ((b[19].toInt() and 255) shl 8)} (183=ARM64)"
        } catch (t: Throwable) { "ELF inspection failed: $t" }
        val loader = File(home, "lib/ld-linux-aarch64.so.1")
        val binary = File(home, "bin/bricktracker")
        val shim = File(home, "lib/libSDL2-2.0.so.0")
        val assets = listOf("runtime.zip", "runtime.id").joinToString("\\n") { name ->
            try { ctx.assets.open(name).use { "asset $name: readable" } }
            catch (t: Throwable) { "asset $name: ERROR $t" }
        }
        val devices = InputDevice.getDeviceIds().joinToString("\\n") { id ->
            InputDevice.getDevice(id)?.let { "input id=$id name=${it.name} sources=0x${Integer.toUnsignedString(it.sources, 16)}" } ?: "input id=$id unavailable"
        }
        return buildString {
            appendLine("OZMA Android diagnostic report")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android build: ${android.os.Build.DISPLAY} | incremental=${android.os.Build.VERSION.INCREMENTAL} | securityPatch=${android.os.Build.VERSION.SECURITY_PATCH}")
            appendLine("Build fingerprint: ${android.os.Build.FINGERPRINT}")
            appendLine("Hardware: board=${android.os.Build.BOARD} device=${android.os.Build.DEVICE} product=${android.os.Build.PRODUCT} hardware=${android.os.Build.HARDWARE}")
            appendLine("Kernel: ${readSmallFile("/proc/version")}")
            appendLine("App process security: ${readProcStatus()}")
            appendLine("Seccomp actions available: ${readSmallFile("/proc/sys/kernel/seccomp/actions_avail")}")
            appendLine("ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Target SDK: 28 | app version: 0.1")
            appendLine("Data directory: ${dataDir.absolutePath}")
            appendLine("Runtime directory: ${home.absolutePath}")
            appendLine(assets)
            appendLine(status(loader)); appendLine(elf(loader))
            appendLine(status(binary)); appendLine(elf(binary))
            appendLine(status(shim)); appendLine(elf(shim))
            appendLine("Extraction stamp: ${File(home, ".id").let { if (it.exists()) it.readText() else "<missing>" }}")
            appendLine("Input devices:"); appendLine(devices)
            appendLine("Run state: phase=$currentStage lastEvent=$lastTraceEvent pid=${lastPid ?: "<not started>"} exitCode=${lastExitCode ?: "<running/unknown>"}")
            appendLine("Connected IPC channels: ${synchronized(connectedIpc) { connectedIpc.toList().sorted().ifEmpty { listOf("<none>") }.joinToString() }}")
            appendLine("Trace file: ${traceFile.absolutePath}")
            appendLine("Recent execution trace:"); appendLine(tailFile(traceFile, 120))
            appendLine("Exit summary:"); appendLine(exitSummary)
            appendLine("Native stdout/stderr log (${logFile.absolutePath}):"); appendLine(tailLog(80))
            appendLine("Crash/logcat excerpt:"); appendLine(logcatExcerpt)
            appendLine("glibc preflight probes:")
            for (name in listOf("glibc-basic", "glibc-pthread-link-only", "glibc-pthread", "glibc-sdl-dlopen")) {
                val probe = File(ctx.filesDir, "ozma-probe-$name.log")
                appendLine("$name: exists=${probe.exists()} bytes=${if (probe.exists()) probe.length() else 0}")
                appendLine(tailFile(probe, 40))
                val debug = ctx.filesDir.listFiles()?.filter { it.name.startsWith("ozma-probe-$name-ld.") }?.maxByOrNull { it.lastModified() }
                if (debug != null) {
                    appendLine("LD_DEBUG ${debug.name}:")
                    appendLine(tailFile(debug, 100))
                }
            }
            appendLine("glibc loader diagnostics (LD_DEBUG):"); appendLine(readLdDebugLog())
            appendLine("Note: app-level logcat visibility is limited by Android; absence of entries does not rule out a native signal or seccomp event.")
        }
    }

}
