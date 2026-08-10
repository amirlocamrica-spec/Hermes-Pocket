package com.hermes.android.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException

/**
 * Records voice messages with MediaRecorder (AAC in an .m4a container).
 *
 * Lifecycle: create -> start -> stop (returns file) -> release.
 * The recorder is a single-use instance; call [create] for each new take.
 */
class AudioRecorder private constructor(
    private val recorder: MediaRecorder,
    private val outputFile: File,
) {

    val outputPath: String get() = outputFile.absolutePath

    /** Starts capturing. Must be called after [create]. */
    fun start() {
        try {
            recorder.prepare()
            recorder.start()
        } catch (e: IOException) {
            release()
            throw e
        } catch (e: RuntimeException) {
            release()
            throw e
        }
    }

    /** Stops recording and returns the finished audio file. */
    fun stop(): File {
        try {
            recorder.stop()
        } catch (e: RuntimeException) {
            // Can happen if recording was too short; the file is unusable.
        } finally {
            recorder.release()
        }
        return outputFile
    }

    fun release() {
        try {
            recorder.release()
        } catch (_: RuntimeException) {
        }
    }

    companion object {

        /** Default max recording length in milliseconds (60s). */
        const val DEFAULT_MAX_DURATION_MS = 60_000L

        /**
         * Creates a recorder writing to [context]'s cache dir.
         * The app records to cache; callers should move/attach the file promptly.
         */
        fun create(context: Context, fileName: String = "voice_${System.currentTimeMillis()}.m4a"): AudioRecorder {
            val dir = File(context.cacheDir, "voice").apply { mkdirs() }
            val file = File(dir, fileName)

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44_100)
            recorder.setOutputFile(file.absolutePath)
            // Safety cap so a forgotten recording can't fill the disk.
            recorder.setMaxDuration(DEFAULT_MAX_DURATION_MS.toInt())

            return AudioRecorder(recorder, file)
        }
    }
}
