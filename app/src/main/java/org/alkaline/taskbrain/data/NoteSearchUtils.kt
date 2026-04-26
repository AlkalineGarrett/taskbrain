package org.alkaline.taskbrain.data

data class SearchMatch(
    val lineIndex: Int,
    val matchStart: Int,
    val matchEnd: Int,
)

data class SnippetLine(
    val text: String,
    val lineIndex: Int,
)

data class ContentSnippet(
    val lines: List<SnippetLine>,
    val matches: List<SearchMatch>,
)

data class NoteSearchResult(
    val note: Note,
    val nameMatches: List<SearchMatch>,
    val contentSnippets: List<ContentSnippet>,
)

object NoteSearchUtils {

    private const val MAX_CONTENT_MATCHES = 3
    private const val CONTEXT_LINES = 2

    fun searchNotes(
        notes: List<Note>,
        query: String,
        searchByName: Boolean,
        searchByContent: Boolean,
    ): Pair<List<NoteSearchResult>, List<NoteSearchResult>> {
        if (query.isEmpty() || (!searchByName && !searchByContent)) {
            return Pair(emptyList(), emptyList())
        }

        val activeResults = mutableListOf<NoteSearchResult>()
        val deletedResults = mutableListOf<NoteSearchResult>()

        for (note in notes) {
            if (note.parentNoteId != null) continue

            val lines = note.content.split("\n")
            val nameMatches = if (searchByName) findMatches(lines.firstOrNull() ?: "", query, 0) else emptyList()
            val contentSnippets = if (searchByContent) findContentSnippets(lines, query) else emptyList()

            if (nameMatches.isEmpty() && contentSnippets.isEmpty()) continue

            val result = NoteSearchResult(note, nameMatches, contentSnippets)
            if (note.state == "deleted") {
                deletedResults.add(result)
            } else {
                activeResults.add(result)
            }
        }

        return Pair(
            NoteFilteringUtils.sortByUpdatedAtDescending(activeResults.map { it.note })
                .map { sorted -> activeResults.first { it.note.id == sorted.id } },
            NoteFilteringUtils.sortByUpdatedAtDescending(deletedResults.map { it.note })
                .map { sorted -> deletedResults.first { it.note.id == sorted.id } },
        )
    }

    private fun findMatches(text: String, query: String, lineIndex: Int): List<SearchMatch> {
        val matches = mutableListOf<SearchMatch>()
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        var startIndex = 0
        while (true) {
            val idx = lowerText.indexOf(lowerQuery, startIndex)
            if (idx < 0) break
            matches.add(SearchMatch(lineIndex, idx, idx + query.length))
            startIndex = idx + 1
        }
        return matches
    }

    private fun findContentSnippets(lines: List<String>, query: String): List<ContentSnippet> {
        val allMatches = mutableListOf<SearchMatch>()
        for (i in 1 until lines.size) {
            for (match in findMatches(lines[i], query, i)) {
                allMatches.add(match)
                if (allMatches.size >= MAX_CONTENT_MATCHES) break
            }
            if (allMatches.size >= MAX_CONTENT_MATCHES) break
        }
        if (allMatches.isEmpty()) return emptyList()

        return mergeIntoSnippets(allMatches, lines)
    }

    private fun mergeIntoSnippets(matches: List<SearchMatch>, lines: List<String>): List<ContentSnippet> {
        val snippets = mutableListOf<ContentSnippet>()
        var currentStart = -1
        var currentEnd = -1
        var currentMatches = mutableListOf<SearchMatch>()

        for (match in matches) {
            val rangeStart = maxOf(1, match.lineIndex - CONTEXT_LINES)
            val rangeEnd = minOf(lines.size - 1, match.lineIndex + CONTEXT_LINES)

            if (currentStart < 0 || rangeStart > currentEnd + 1) {
                if (currentStart >= 0) {
                    snippets.add(buildSnippet(currentStart, currentEnd, currentMatches, lines))
                }
                currentStart = rangeStart
                currentEnd = rangeEnd
                currentMatches = mutableListOf(match)
            } else {
                currentEnd = maxOf(currentEnd, rangeEnd)
                currentMatches.add(match)
            }
        }

        if (currentStart >= 0) {
            snippets.add(buildSnippet(currentStart, currentEnd, currentMatches, lines))
        }

        return snippets
    }

    private fun buildSnippet(
        start: Int,
        end: Int,
        matches: List<SearchMatch>,
        lines: List<String>,
    ): ContentSnippet {
        val snippetLines = (start..end).map { i -> SnippetLine(lines[i], i) }
        return ContentSnippet(snippetLines, matches)
    }
}
