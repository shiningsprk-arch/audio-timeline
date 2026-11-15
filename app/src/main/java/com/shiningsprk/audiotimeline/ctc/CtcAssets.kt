package com.shiningsprk.audiotimeline.ctc

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Everything the aligner needs at runtime.
 *
 * The ONNX graphs ship inside the APK as `jniLibs` entries (`libctc*.so`), because
 * assets are not real files and ONNX Runtime can only load by path. Android extracts
 * native libs on install, so no 600 MB copy is needed at first launch.
 */
class CtcAssets private constructor(
    val vocab: CtcVocab,
    val t2s: Map<Char, Char>,
    val alignerModel: File,
    val separatorModel: File?,
) {

    val hasSeparator: Boolean get() = separatorModel != null

    companion object {
        private const val ALIGNER_LIB = "libctcmodel.so"
        private const val SEPARATOR_LIB = "libdemucsmodel.so"

        fun load(context: Context): CtcAssets {
            val vocab = context.assets.open("ctc_vocab.txt").use { stream ->
                CtcVocab.load(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)))
            }
            val t2s = context.assets.open("t2s.txt").use { stream ->
                T2sTable.load(BufferedReader(InputStreamReader(stream, Charsets.UTF_8)))
            }
            val libDir = File(context.applicationInfo.nativeLibraryDir)
            val aligner = File(libDir, ALIGNER_LIB)
            require(aligner.isFile) { "对齐模型缺失（$ALIGNER_LIB），请重新安装完整版 APK" }
            val separator = File(libDir, SEPARATOR_LIB).takeIf { it.isFile }
            return CtcAssets(vocab, t2s, aligner, separator)
        }
    }
}
