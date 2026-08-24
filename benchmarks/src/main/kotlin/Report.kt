package io.github.kromus.benchmarks

/** A markdown table, accumulated as the suite runs and rendered into the report at the end. */
class Report(
    private val title: String,
) {
    private val header = StringBuilder()
    private val rows = mutableListOf<List<String>>()
    private var columns: List<String> = emptyList()

    fun columns(vararg names: String) {
        columns = names.toList()
    }

    fun row(vararg cells: Any) {
        rows.add(cells.map { it.toString() })
    }

    fun note(text: String) {
        header.append(text).append('\n')
    }

    fun render(): String {
        val out = StringBuilder("\n### $title\n\n")
        if (header.isNotEmpty()) out.append(header).append('\n')
        out.append(columns.joinToString(" | ", "| ", " |")).append('\n')
        out.append(columns.joinToString(" | ", "| ", " |") { "---" }).append('\n')
        for (row in rows) out.append(row.joinToString(" | ", "| ", " |")).append('\n')
        return out.toString()
    }
}
