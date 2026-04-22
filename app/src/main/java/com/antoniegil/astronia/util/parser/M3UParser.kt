package com.antoniegil.astronia.util.parser

data class M3UChannel(
    val id: String,
    val name: String,
    val url: String,
    val group: String = "",
    val logoUrl: String = "",
    val relationId: String = "",
    val licenseType: String? = null,
    val licenseKey: String? = null
)

object M3UParser {
    
    private const val M3U_HEADER_MARK = "#EXTM3U"
    private const val M3U_INFO_MARK = "#EXTINF:"
    private const val KODI_MARK = "#KODIPROP:"
    private const val VLC_OPT_MARK = "#EXTVLCOPT:"
    private const val M3U_TVG_ID_MARK = "tvg-id"
    private const val M3U_TVG_NAME_MARK = "tvg-name"
    private const val M3U_TVG_LOGO_MARK = "tvg-logo"
    private const val M3U_GROUP_TITLE_MARK = "group-title"
    private const val KODI_LICENSE_TYPE = "inputstream.adaptive.license_type"
    private const val KODI_LICENSE_KEY = "inputstream.adaptive.license_key"
    
    private val infoRegex = """(-?\d+)(.*),(.+)""".toRegex()
    private val metadataRegex = """([\w-_.]+)=\s*(?:"([^"]*)"|(\S+))""".toRegex()
    private val propRegex = """([^=]+)=(.+)""".toRegex()
    
    fun parse(content: String): Pair<List<M3UChannel>, String?> {
        val channels = mutableListOf<M3UChannel>()
        
        val epgUrl = content.lineSequence()
            .firstOrNull { it.startsWith(M3U_HEADER_MARK) }
            ?.let { extractEpgUrl(it) }
        
        val lines = content.lineSequence()
            .filter { it.isNotEmpty() }
            .map { it.trimEnd() }
            .dropWhile { it.startsWith(M3U_HEADER_MARK) }
            .iterator()
        
        var currentLine: String
        var infoMatch: MatchResult? = null
        val extraProps = mutableListOf<MatchResult>()
        
        while (lines.hasNext()) {
            currentLine = lines.next()
            
            while (currentLine.startsWith("#")) {
                if (currentLine.startsWith(M3U_INFO_MARK)) {
                    infoMatch = infoRegex.matchEntire(currentLine.drop(M3U_INFO_MARK.length).trim())
                }
                if (currentLine.startsWith(KODI_MARK)) {
                    propRegex
                        .matchEntire(currentLine.drop(KODI_MARK.length).trim())
                        ?.also { extraProps += it }
                }
                if (currentLine.startsWith(VLC_OPT_MARK)) {
                    propRegex
                        .matchEntire(currentLine.drop(VLC_OPT_MARK.length).trim())
                        ?.also { extraProps += it }
                }
                if (lines.hasNext()) {
                    currentLine = lines.next()
                } else break
            }
            
            if (infoMatch == null && !currentLine.startsWith("#")) continue
            
            if (!currentLine.startsWith("#")) {
                val title = infoMatch?.groups?.get(3)?.value.orEmpty().trim()
                val metadata = buildMap {
                    val text = infoMatch?.groups?.get(2)?.value.orEmpty().trim()
                    val matches = metadataRegex.findAll(text)
                    for (match in matches) {
                        val key = match.groups[1]!!.value
                        val value = match.groups[2]?.value?.ifBlank { null } ?: continue
                        put(key.trim(), value.trim())
                    }
                }
                
                val props = buildMap {
                    for (match in extraProps) {
                        val key = match.groups[1]!!.value
                        val value = match.groups[2]?.value?.ifBlank { null } ?: continue
                        put(key.trim(), value.trim())
                    }
                }
                
                val tvgId = metadata[M3U_TVG_ID_MARK].orEmpty()
                val tvgName = metadata[M3U_TVG_NAME_MARK].orEmpty()
                val relationId = tvgId.ifEmpty { tvgName }
                val finalTitle = title.ifEmpty { "Channel ${channels.size + 1}" }
                
                val headers = mutableMapOf<String, String>()
                props["http-user-agent"]?.let { headers["User-Agent"] = it }
                props["http-referrer"]?.let { headers["Referer"] = it }
                props["user-agent"]?.let { headers["User-Agent"] = it }
                props["referer"]?.let { headers["Referer"] = it }
                
                var finalUrl = currentLine
                if (headers.isNotEmpty()) {
                    val headerString = headers.entries.joinToString("&") { "${it.key}=${it.value}" }
                    finalUrl = "$finalUrl|$headerString"
                }

                channels.add(
                    M3UChannel(
                        id = "${currentLine.hashCode()}_${channels.size}",
                        name = finalTitle,
                        url = finalUrl,
                        group = metadata[M3U_GROUP_TITLE_MARK].orEmpty(),
                        logoUrl = metadata[M3U_TVG_LOGO_MARK].orEmpty(),
                        relationId = relationId,
                        licenseType = props[KODI_LICENSE_TYPE],
                        licenseKey = props[KODI_LICENSE_KEY]
                    )
                )
                
                infoMatch = null
                extraProps.clear()
            }
        }
        
        return channels to epgUrl
    }
    
    private fun extractEpgUrl(line: String): String? {
        val xTvgUrlMatch = Regex("""x-tvg-url\s*=\s*"([^"]+)"""").find(line)
        val urlTvgMatch = Regex("""url-tvg\s*=\s*"([^"]+)"""").find(line)
        
        return xTvgUrlMatch?.groupValues?.get(1) ?: urlTvgMatch?.groupValues?.get(1)
    }
}
