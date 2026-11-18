package io.github.sherazi5110.cloudstream

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android's regex engine (ICU) is stricter than Java's: an unescaped literal `}` or `{`, or `[^]]`, compiles on a
 * computer but throws on the phone (issue #41: "Wrong match?" crashed). Every Regex("…") in the app must avoid them.
 */
class RegexIcuSafetyTest {
    @Test fun noIcuIncompatiblePatterns() {
        val src = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        val literal = Regex("""Regex\("((?:[^"\\]|\\.)*)"""")
        val bad = mutableListOf<String>()
        src.walk().filter { it.extension == "kt" }.forEach { f ->
            f.readLines().forEachIndexed { i, line ->
                literal.findAll(line).forEach { m ->
                    val r = m.groupValues[1].replace("\\\\", "\\")
                    val stripped = r.replace(Regex("""\\[{}\[\]]"""), "")
                        .replace(Regex("""\\[pP]\{\w+\}"""), "")
                        .replace(Regex("""\{\d+(,\d*)?\}"""), "")
                        .replace(Regex("""\$\{[^}]*\}"""), "") // Kotlin string templates
                    if ('{' in stripped || '}' in stripped || "[^]" in r) bad += "${f.name}:${i + 1}: ${m.groupValues[1]}"
                }
            }
        }
        assertTrue("Patterns Android can't compile:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
