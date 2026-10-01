package com.goviet.keyboard.ui

val secondaryKeyMap = mapOf(
    "a" to "â",
    "d" to "đ",
    "e" to "ê",
    "o" to "ô",
    "u" to "ư",
    "i" to "í",
    "y" to "ý"
)

val longPressLetterMap = mapOf(
    // Vowels and d — full accented letters like Gboard
    "a" to listOf(
        "â", "ấ", "ầ", "ẩ", "ẫ", "ậ",
        "ă", "ắ", "ằ", "ẳ", "ẵ", "ặ",
        "á", "à", "ả", "ã", "ạ"
    ),
    "d" to listOf("đ"),
    "e" to listOf(
        "ê", "ế", "ề", "ể", "ễ", "ệ",
        "é", "è", "ẻ", "ẽ", "ẹ"
    ),
    "i" to listOf("í", "ì", "ỉ", "ĩ", "ị"),
    "o" to listOf(
        "ô", "ố", "ồ", "ổ", "ỗ", "ộ",
        "ơ", "ớ", "ờ", "ở", "ỡ", "ợ",
        "ó", "ò", "ỏ", "õ", "ọ"
    ),
    "u" to listOf(
        "ư", "ứ", "ừ", "ử", "ữ", "ự",
        "ú", "ù", "ủ", "ũ", "ụ"
    ),
    "y" to listOf("ý", "ỳ", "ỷ", "ỹ", "ỵ")
)

val symbolLongPressMap = mapOf(
    // Common punctuation variants
    "!" to listOf("¡"),
    "?" to listOf("¿"),
    "-" to listOf("–", "—", "·"),
    "\"" to listOf("“", "”"),
    "'" to listOf("‘", "’"),
    "%" to listOf("‰"),
    "+" to listOf("±"),
    "/" to listOf("\\", "|"),
    "\\" to listOf("/", "|"),
    "|" to listOf("/", "\\"),
    "`" to listOf("~"),
    "=" to listOf("≠", "≈"),
    "<" to listOf("≤", "«"),
    ">" to listOf("≥", "»"),
    "&" to listOf("§"),
    "*" to listOf("★"),
    "(" to listOf("[", "{"),
    ")" to listOf("]"),
    "[" to listOf("{"),
    "]" to listOf("}"),
    "{" to listOf("["),
    "}" to listOf("]"),
    "~" to listOf("`"),
    "_" to listOf("—", "–"),
    "," to listOf(";", "、"),
    "." to listOf("…", ".com", ".vn"),
    ":" to listOf(";"),
    ";" to listOf(":"),
    // Currency variants
    "₫" to listOf("$", "€", "£", "¥", "₩", "¢"),
    "$" to listOf("₫", "€", "£", "¥", "₩"),
    "€" to listOf("$", "£"),
    "£" to listOf("€", "$"),
    "¥" to listOf("₩"),
    "₩" to listOf("¥"),
    "₹" to listOf("₨"),
    "₽" to listOf("₴"),
    // Math variants
    "±" to listOf("+"),
    "−" to listOf("-"),
    "×" to listOf("·"),
    "÷" to listOf("/"),
    "≠" to listOf("="),
    "≈" to listOf("~"),
    "≤" to listOf("<"),
    "≥" to listOf(">"),
    "∞" to listOf("°"),
    "√" to listOf("∛"),
    "∫" to listOf("∬"),
    "Δ" to listOf("δ"),
    // Quote variants
    "“" to listOf("\""),
    "”" to listOf("\""),
    "‘" to listOf("'"),
    "’" to listOf("'"),
    "«" to listOf("<"),
    "»" to listOf(">"),
    // Misc
    "°" to listOf("℃", "℉"),
    "℃" to listOf("℉"),
    "℉" to listOf("℃"),
    "§" to listOf("¶"),
    "©" to listOf("®", "™"),
    "®" to listOf("©"),
    "™" to listOf("©"),
    // Arrow pairs
    "↑" to listOf("↓"),
    "↓" to listOf("↑"),
    "←" to listOf("→"),
    "→" to listOf("←"),
    "…" to listOf("⋯")
)
