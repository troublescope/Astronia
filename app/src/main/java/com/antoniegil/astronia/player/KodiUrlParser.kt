package com.antoniegil.astronia.player

internal object KodiUrlParser {
    private const val HTTP_OPTION_UA = "user-agent"
    
    fun parseKodiUrlOptions(url: String): Map<String, String?> {
        val index = url.indexOf('|')
        if (index == -1) return emptyMap()
        val optionsPart = url.drop(index + 1)

        // Support both & (kodi) and \n (generic) as delimiters
        val options = optionsPart.split(Regex("[&\n]"))

        return options
            .filter { it.isNotBlank() }
            .associate {
                // Support both : (standard) and = (kodi) as separators
                val pair = when {
                    it.contains(": ") -> it.split(": ", limit = 2)
                    it.contains("=") -> it.split("=", limit = 2)
                    else -> listOf(it, "")
                }

                val key = pair.getOrNull(0).orEmpty().trim()
                val value = pair.getOrNull(1)?.trim()
                key to value
            }
    }
    
    fun extractUserAgent(url: String): String? {
        val kodiOptions = parseKodiUrlOptions(url)
        return kodiOptions[HTTP_OPTION_UA]
    }

    fun extractHeaders(url: String): Map<String, String> {
        val kodiOptions = parseKodiUrlOptions(url)
        return kodiOptions.filterValues { it != null }.mapValues { it.value!! }
    }
    
    fun cleanUrl(url: String): String {
        val index = url.indexOf('|')
        return if (index == -1) url else url.take(index)
    }
}
