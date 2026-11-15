package com.shiningsprk.audiotimeline.ctc

import java.io.BufferedReader

/** Traditional -> simplified character map, so lyrics written either way still align. */
object T2sTable {

    fun load(reader: BufferedReader): Map<Char, Char> {
        val map = HashMap<Char, Char>(4096)
        reader.useLines { lines ->
            lines.forEach { line ->
                val parts = line.trim().split(' ')
                if (parts.size == 2 && parts[0].length == 1 && parts[1].length == 1) {
                    map[parts[0][0]] = parts[1][0]
                }
            }
        }
        return map
    }
}
