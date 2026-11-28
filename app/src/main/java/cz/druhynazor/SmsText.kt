package cz.druhynazor

/** Text SMS pro rodinu: bez háčků a zkrácený, aby se vešel do co nejmenšího počtu placených SMS. */
object SmsText {

    /** Kolik znaků se vejde do jedné SMS bez háčků a čárek. S háčky by to bylo jen 70. */
    const val ONE_SMS = 160
    const val PART = 153

    /**
     * Text pro SMS bez háčků, čárek a zvláštních znaků. Jediný háček nebo emoji přepne celou SMS
     * do jiného kódování, kde se vejde jen 70 znaků místo 160, a operátor pak účtuje víc SMS.
     */
    fun clean(s: String): String {
        val t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('„', '"').replace('“', '"').replace('”', '"').replace('‚', '\'').replace('‘', '\'').replace('’', '\'')
            .replace('–', '-').replace('—', '-').replace("…", "...").replace("€", "EUR").replace('\u00A0', ' ')
            .replace('[', '(').replace(']', ')').replace('{', '(').replace('}', ')')
        val sb = StringBuilder()
        for (c in t) {
            when {
                c == '\n' -> sb.append(c)
                c.code in 32..126 && c !in "\\^~|`" -> sb.append(c)
                c.isWhitespace() -> sb.append(' ')
                // emoji a ostatní znaky vynecháme
            }
        }
        return sb.toString().replace(Regex("[ \t]{2,}"), " ").replace(Regex("\n{2,}"), "\n").trim()
    }

    /** Zkrátí text na max znaků, pokud možno na konci slova, a přidá „...“. */
    fun fit(s: String, max: Int): String {
        if (s.length <= max) return s
        if (max <= 3) return ""
        val cut = s.take(max - 3)
        val space = cut.lastIndexOf(' ')
        return (if (space > max / 2) cut.take(space) else cut).trimEnd() + "..."
    }

    /** Nejvýš kolik znaků smí mít SMS, aby nepřesáhla parts částí. */
    fun limit(parts: Int) = if (parts <= 1) ONE_SMS else PART * parts
}
