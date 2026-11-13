package com.shiningsprk.audiotimeline.asr

import java.io.BufferedReader

/** Traditional -> simplified character map, used only to make text matching tolerant. */
class T2sTable private constructor(private val map: HashMap<Char, Char>) {

    fun map(): Map<Char, Char> = map

    companion object {
        fun load(reader: BufferedReader): T2sTable {
            val map = HashMap<Char, Char>(4096)
            reader.useLines { lines ->
                lines.forEach { line ->
                    val parts = line.trim().split(' ')
                    if (parts.size == 2 && parts[0].length == 1 && parts[1].length == 1) {
                        map[parts[0][0]] = parts[1][0]
                    }
                }
            }
            return T2sTable(map)
        }
    }
}
