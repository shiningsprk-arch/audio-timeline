import com.shiningsprk.audiotimeline.align.RecChar
import com.shiningsprk.audiotimeline.align.TextAligner
import com.shiningsprk.audiotimeline.asr.T2sTable
import com.shiningsprk.audiotimeline.dsp.MelFrontend
import com.shiningsprk.audiotimeline.dsp.SpeechRegion
import com.shiningsprk.audiotimeline.dsp.Vad
import com.shiningsprk.audiotimeline.export.SubtitleWriter
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * JVM harness: replays the Python reference pipeline through the shipped Kotlin code.
 *
 *   kotlinc <core sources> tools/verify/JvmCheck.kt -include-runtime -d check.jar
 *   java -jar check.jar <assets dir> <reference dir>
 */
private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("PASS  $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    } else {
        failures++
        println("FAIL  $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    }
}

private fun readFloats(path: File): FloatArray {
    val bytes = path.readBytes()
    val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val out = FloatArray(fb.remaining())
    fb.get(out)
    return out
}

private fun readPairs(path: File): List<Pair<Double, Double>> =
    path.readLines().filter { it.isNotBlank() }.map { line ->
        val p = line.split('\t')
        p[0].toDouble() to p[1].toDouble()
    }

fun main(args: Array<String>) {
    val assets = File(args[0])
    val ref = File(args[1])

    // ---------------------------------------------------------------- mel frontend
    val audio = readFloats(File(ref, "audio.f32"))
    val filters = readFloats(File(assets, "mel_filters.bin"))
    check("mel filters size", filters.size == 80 * 201, "${filters.size}")

    val mel = FloatArray(MelFrontend.N_MEL * MelFrontend.N_FRAMES)
    val started = System.currentTimeMillis()
    MelFrontend.compute(audio, 0, filters, mel)
    val melMs = System.currentTimeMillis() - started

    val refMel = readFloats(File(ref, "mel.f32"))
    var maxDiff = 0f
    var sumDiff = 0.0
    for (i in mel.indices) {
        val d = abs(mel[i] - refMel[i])
        if (d > maxDiff) maxDiff = d
        sumDiff += d
    }
    check("mel matches whisper reference", maxDiff < 2e-3f, "max=$maxDiff mean=${sumDiff / mel.size}, ${melMs}ms")

    // ---------------------------------------------------------------- vad
    val regions = Vad.detect(audio)
    val refRegions = readPairs(File(ref, "regions.tsv"))
    check("vad region count", regions.size == refRegions.size, "${regions.size} vs ${refRegions.size}")
    var regionErr = 0.0
    for (i in minOf(regions.size, refRegions.size) - 1 downTo 0) {
        regionErr = maxOf(regionErr, abs(regions[i].start - refRegions[i].first), abs(regions[i].end - refRegions[i].second))
    }
    check("vad region times", regionErr < 0.02, "max err=$regionErr")

    // ---------------------------------------------------------------- char timeline
    val windows = readPairs(File(ref, "windows.tsv"))
    val texts = File(ref, "texts.txt").readLines().filter { it.isNotEmpty() }
    val chars = TextAligner.buildCharTimeline(texts, windows, regions)
    val refChars = File(ref, "chars.tsv").readLines().filter { it.isNotBlank() }.map {
        val p = it.split('\t')
        Triple(p[0].toInt().toChar(), p[1].toDouble(), p[2].toDouble())
    }
    check("char count", chars.size == refChars.size, "${chars.size} vs ${refChars.size}")
    var charErr = 0.0
    var charMismatch = 0
    for (i in 0 until minOf(chars.size, refChars.size)) {
        if (chars[i].ch != refChars[i].first) charMismatch++
        charErr = maxOf(charErr, abs(chars[i].start - refChars[i].second), abs(chars[i].end - refChars[i].third))
    }
    check("char texts", charMismatch == 0, "$charMismatch different")
    check("char times", charErr < 0.02, "max err=$charErr")

    // ---------------------------------------------------------------- alignment
    val t2s = T2sTable.load(BufferedReader(FileReader(File(assets, "t2s.txt"))))
    val lines = File(ref, "lines.txt").readLines().filter { it.isNotBlank() }
    val meta = File(ref, "meta.txt").readLines().associate { it.split('=').let { p -> p[0] to p[1] } }
    val duration = meta["duration"]!!.toDouble()
    val result = TextAligner.align(lines, chars, regions, t2s.map(), duration)
    val refSpans = readPairs(File(ref, "spans.tsv"))
    check("line count", result.lines.size == refSpans.size, "${result.lines.size}")
    var spanErr = 0.0
    for (i in 0 until minOf(result.lines.size, refSpans.size)) {
        spanErr = maxOf(
            spanErr,
            abs(result.lines[i].start - refSpans[i].first),
            abs(result.lines[i].end - refSpans[i].second),
        )
    }
    check("line times", spanErr < 0.02, "max err=$spanErr")

    check(
        "matched characters",
        result.matchedChars == meta["matched"]!!.toInt(),
        "${result.matchedChars} vs ${meta["matched"]}",
    )
    check("match rate sane", result.matchRate in 0.5f..1.0f, "%.0f%%".format(result.matchRate * 100))

    // ---------------------------------------------------------------- formatting
    val srt = SubtitleWriter.srt(result.lines)
    check("srt starts with index", srt.startsWith("1\n00:"), srt.lineSequence().first())
    check("srt time format", Regex("""00:\d{2}:\d{2},\d{3} --> 00:\d{2}:\d{2},\d{3}""").containsMatchIn(srt))
    val lrc = SubtitleWriter.lrc(result.lines)
    check("lrc format", lrc.startsWith("[00:"), lrc.lineSequence().first())

    // ---------------------------------------------------------------- lexer sanity
    val tokenizer = com.shiningsprk.audiotimeline.asr.WhisperTokenizer.load(
        BufferedReader(FileReader(File(assets, "vocab.txt"))),
        BufferedReader(FileReader(File(assets, "byte_table.txt"))),
    )
    val probes = File(ref, "tokens.tsv").readLines().filter { it.isNotBlank() }.map {
        val parts = it.split('\t')
        parts[0].toInt() to parts.getOrElse(1) { "" }
    }
    var tokenFailures = 0
    for ((id, expected) in probes) {
        if (tokenizer.decode(intArrayOf(id)) != expected) tokenFailures++
    }
    check("tokenizer detokenises", tokenFailures == 0, "$tokenFailures/${probes.size} mismatched")

    println()
    if (failures == 0) {
        println("ALL CHECKS PASSED")
    } else {
        println("$failures CHECK(S) FAILED")
        kotlin.system.exitProcess(1)
    }
}
