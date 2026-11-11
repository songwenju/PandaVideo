package dev.pandavideo.data.html

/** Small read-only markup tree for the verified upstream card/playlist structures. */
internal class HtmlNode(val tag: String, private val attributes: Map<String, String> = emptyMap()) {
    private val children = mutableListOf<HtmlNode>()
    private var content = ""
    fun attr(key: String) = attributes[key].orEmpty()
    fun hasClass(value: String) = value in attr("class").split(Regex("\\s+"))
    fun text(): String = (content + children.joinToString(" ") { it.text() }).replace(Regex("\\s+"), " ").trim()
    fun descendants(): List<HtmlNode> = children.flatMap { listOf(it) + it.descendants() }

    companion object {
        private val tokens = Regex("<!--[\\s\\S]*?-->|<(script|style)\\b[^>]*>[\\s\\S]*?</\\1\\s*>|<[^>]+>|[^<]+", RegexOption.IGNORE_CASE)
        private val attrs = Regex("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
        private val voidTags = setOf("img", "br", "hr", "input", "meta", "link", "source", "area", "base", "embed", "wbr")
        fun parse(html: String): HtmlNode {
            val root = HtmlNode("root")
            val stack = mutableListOf(root)
            for (match in tokens.findAll(html)) {
                val token = match.value
                if (token.startsWith("<!--") || match.groupValues[1].isNotEmpty() || token.startsWith("<!")) continue
                if (token.startsWith("</")) {
                    val tag = token.substring(2).substringBefore('>').trim().lowercase()
                    val index = stack.indexOfLast { it.tag == tag }
                    if (index > 0) while (stack.size > index) stack.removeAt(stack.lastIndex)
                } else if (token.startsWith("<")) {
                    val tag = token.substring(1).takeWhile { it.isLetterOrDigit() }.lowercase()
                    if (tag.isBlank()) continue
                    val attributes = attrs.findAll(token).associate {
                        it.groupValues[1].lowercase() to decode(it.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty())
                    }
                    val node = HtmlNode(tag, attributes)
                    stack.last().children += node
                    if (tag !in voidTags && !token.endsWith("/>") && stack.size < 128) stack += node
                } else {
                    stack.last().children += HtmlNode("#text").also { it.content = decode(token) }
                }
            }
            return root
        }
        private fun decode(value: String): String = Regex("&(#x[\\da-fA-F]+|#\\d+|amp|quot|apos|lt|gt|nbsp);").replace(value) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> "&"; "quot" -> "\""; "apos" -> "'"; "lt" -> "<"; "gt" -> ">"; "nbsp" -> " "
                else -> {
                    val number = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                    when {
                        number == null || number !in 1..0x10ffff || number in 0xd800..0xdfff -> match.value
                        number <= 0xffff -> number.toChar().toString()
                        else -> { val n = number - 0x10000; "${(0xd800 + (n shr 10)).toChar()}${(0xdc00 + (n and 1023)).toChar()}" }
                    }
                }
            }
        }
    }
}
