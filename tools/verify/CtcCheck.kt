import com.shiningsprk.audiotimeline.ctc.CtcAligner
import com.shiningsprk.audiotimeline.ctc.CtcPosteriors
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("PASS  $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    else {
        failures++
        println("FAIL  $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    }
}

fun main(args: Array<String>) {
    val dir = File(args[0])
    val meta = File(dir, "meta.txt").readLines().associate {
        val p = it.split('=')
        p[0] to p[1]
    }
    val frames = meta["frames"]!!.toInt()
    val vocab = meta["vocab"]!!.toInt()
    val frameSec = meta["frame_sec"]!!.toDouble()

    val lpBytes = File(dir, "lp.f32").readBytes()
    val fb = ByteBuffer.wrap(lpBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val lp = FloatArray(fb.remaining())
    fb.get(lp)

    val idsBytes = File(dir, "tokens.i32").readBytes()
    val ib = ByteBuffer.wrap(idsBytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
    val ids = IntArray(ib.remaining())
    ib.get(ids)

    val expected = File(dir, "spans.tsv").readLines().filter { it.isNotBlank() }.map { line ->
        val p = line.split('\t')
        if (p[0] == "nan") null else p[0].toDouble() to p[1].toDouble()
    }

    val started = System.currentTimeMillis()
    val post = CtcPosteriors.fromLogProbs(frames, vocab, lp)
    val quantMs = System.currentTimeMillis() - started
    val alignStart = System.currentTimeMillis()
    val spans = CtcAligner.align(post, ids, frameSec)
    val alignMs = System.currentTimeMillis() - alignStart

    check("span count", spans.size == expected.size, "${spans.size} vs ${expected.size}")

    var nullMismatch = 0
    var maxErr = 0.0
    for (i in expected.indices) {
        val e = expected[i]
        val a = spans[i]
        if ((e == null) != (a == null)) {
            nullMismatch++
            continue
        }
        if (e != null && a != null) {
            maxErr = maxOf(maxErr, abs(a.start - e.first), abs(a.end - e.second))
        }
    }
    check("null pattern", nullMismatch == 0, "$nullMismatch differ")
    check("span times", maxErr < 0.03, String.format(java.util.Locale.US, "max err %.3fs", maxErr))
    val worst = expected.indices.mapNotNull { i ->
        val e = expected[i] ?: return@mapNotNull null
        val a = spans[i] ?: return@mapNotNull null
        val d = maxOf(abs(a.start - e.first), abs(a.end - e.second))
        if (d > 0.03) Triple(i, e, a) else null
    }.sortedByDescending { maxOf(abs(it.third.start - it.second.first), abs(it.third.end - it.second.second)) }
    for ((i, e, a) in worst.take(5)) {
        println("   #%d expected %.2f-%.2f got %.2f-%.2f".format(i, e.first, e.second, a.start, a.end))
    }

    val durations = spans.filterNotNull().map { it.end - it.start }
    val median = durations.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
    check("median char duration", median in 0.01..0.6, String.format(java.util.Locale.US, "%.3fs", median))
    println("quantise %d ms, viterbi %d ms, frames %d, tokens %d".format(quantMs, alignMs, frames, ids.size))

    println()
    if (failures == 0) println("ALL CHECKS PASSED") else {
        println("$failures CHECK(S) FAILED")
        kotlin.system.exitProcess(1)
    }
}
