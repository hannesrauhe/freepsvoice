package eu.rauhe.freepsvoice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM16 mono -> WAV container (what Voxtral expects). Pure, unit-testable. */
object Wav {
    const val SAMPLE_RATE = 16_000

    fun encode(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt(36 + pcm.size)
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)          // fmt chunk size
        h.putShort(1)         // PCM
        h.putShort(1)         // mono
        h.putInt(sampleRate)
        h.putInt(sampleRate * 2) // byte rate
        h.putShort(2)         // block align
        h.putShort(16)        // bits
        h.put("data".toByteArray())
        h.putInt(pcm.size)
        return h.array() + pcm
    }
}
