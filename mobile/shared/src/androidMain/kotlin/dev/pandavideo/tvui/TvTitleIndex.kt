package dev.pandavideo.tvui

import dev.pandavideo.core.model.CatalogItem
import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/** Searches known titles only; unknown catalog coverage still belongs to online search. */
internal class TvTitleIndex(titles: List<CatalogItem>) {
    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }
    private data class Entry(val item: CatalogItem, val title: String, val pinyin: String, val initials: String)
    private val entries = titles.distinctBy { it.url }.map { item ->
        val syllables = item.title.mapNotNull { char ->
            if (!char.isLetterOrDigit()) null else
                PinyinHelper.toHanyuPinyinStringArray(char, format)?.firstOrNull()
                    ?: char.lowercase()
        }
        Entry(item, normalize(item.title), syllables.joinToString(""),
            syllables.joinToString("") { it.take(1) })
    }

    fun search(query: String, kind: String): List<CatalogItem> {
        if (normalize(query).isEmpty()) return emptyList()
        val ranks = ranks(query)
        // Default dictionary pronunciation is a convenience, not identity resolution.
        // Final playback still passes the complete title/year/type to AppState.match.
        return entries.asSequence().filter { kind == "all" || it.item.kind == kind }
            .filter { ranks.getValue(it.item.url) < 4 }
            .sortedBy { ranks.getValue(it.item.url) }
            .take(60).map { it.item }.toList()
    }

    /** Ranks every result without applying the local catalog's sixty-title display limit. */
    fun ranks(query: String): Map<String, Int> {
        val value = normalize(query)
        return entries.associate { entry ->
            entry.item.url to when {
                value.isEmpty() || entry.title == value -> 0
                entry.pinyin == value || entry.initials == value -> 1
                listOf(entry.title, entry.pinyin, entry.initials).any { it.startsWith(value) } -> 2
                listOf(entry.title, entry.pinyin, entry.initials).any { value in it } -> 3
                else -> 4
            }
        }
    }

    private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
}
