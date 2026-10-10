package eu.rauhe.freepsvoice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/** Mic -> 16 kHz mono PCM16 in a background thread; stop() returns WAV bytes. */
class Recorder {
    @Volatile private var recording = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val out = ByteArrayOutputStream()

    @SuppressLint("MissingPermission") // caller must hold RECORD_AUDIO
    fun start() {
        out.reset()
        val min = AudioRecord.getMinBufferSize(
            Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(min > 0) { "16 kHz Mono Aufnahme wird nicht unterstuetzt" }
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            min * 4,
        )
        check(r.state == AudioRecord.STATE_INITIALIZED) { "Mikrofon nicht verfuegbar" }
        record = r
        r.startRecording()
        recording = true
        val maxBytes = Wav.SAMPLE_RATE * 2 * MAX_SECONDS
        val buf = ByteArray(min)
        thread = Thread {
            while (recording && out.size() < maxBytes) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) out.write(buf, 0, n)
            }
        }.apply { start() }
    }

    fun stop(): ByteArray {
        recording = false
        thread?.join(2_000)
        thread = null
        record?.runCatching { stop(); release() }
        record = null
        return Wav.encode(out.toByteArray())
    }

    companion object {
        const val MAX_SECONDS = 15
    }
}
