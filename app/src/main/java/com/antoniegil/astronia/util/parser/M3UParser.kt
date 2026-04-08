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
    
    private const val M3U_INFO_MARK = "#EXTINF:"
    private const val KODI_MARK = "#KODIPROP:"
    private const val M3U_TVG_ID_MARK = "tvg-id"
    private const val M3U_TVG_NAME_MARK = "tvg-name"
    private const val M3U_TVG_LOGO_MARK = "tvg-logo"
    private const val M3U_GROUP_TITLE_MARK = "group-title"
    private const val KODI_LICENSE_TYPE = "inputstream.adaptive.license_type"
    private const val KODI_LICENSE_KEY = "inputstream.adaptive.license_key"
    
    private val metadataRegex = """([\w-_.]+)=\s*(?:"([^"]*)"|(\S+))""".toRegex()
    private val kodiPropRegex = """([^=]+)=(.+)""".toRegex()
    
    fun parse(content: String): Pair<List<M3UChannel>, String?> {
        val channels = mutableListOf<M3UChannel>()
        var epgUrl: String? = null
        
        val lines = content.lineSequence()
            .filter { it.isNotEmpty() }
            .map { it.trimEnd() }
            .iterator()
        
        var currentLine: String
        var metadata = emptyMap<String, String>()
        var title = ""
        val kodiMatches = mutableListOf<MatchResult>()
        
        while (lines.hasNext()) {
            currentLine = lines.next()
            
            if (currentLine.startsWith("#EXTM3U")) {
                epgUrl = extractEpgUrl(currentLine)
                continue
            }
            
            while (currentLine.startsWith("#")) {
                if (currentLine.startsWith(M3U_INFO_MARK)) {
                    val infoContent = currentLine.drop(M3U_INFO_MARK.length).trim()
                    val commaIndex = infoContent.lastIndexOf(',')
                    if (commaIndex != -1) {
                        val metadataText = infoContent.take(commaIndex)
                        title = infoContent.substring(commaIndex + 1).trim()
                        metadata = parseMetadata(metadataText)
                    }
                }
                if (currentLine.startsWith(KODI_MARK)) {
                    kodiPropRegex
                        .matchEntire(currentLine.drop(KODI_MARK.length).trim())
                        ?.also { kodiMatches += it }
                }
                if (lines.hasNext()) {
                    currentLine = lines.next()
                } else break
            }
            
            if (metadata.isEmpty() && !currentLine.startsWith("#")) continue
            
            if (!currentLine.startsWith("#") &&
                (currentLine.startsWith("http") || currentLine.startsWith("rtmp") ||
                 currentLine.startsWith("rtsp") || currentLine.startsWith("udp"))) {
                
                val tvgId = metadata[M3U_TVG_ID_MARK].orEmpty()
                val tvgName = metadata[M3U_TVG_NAME_MARK].orEmpty()
                val relationId = tvgId.ifEmpty { tvgName }
                val finalTitle = title.ifEmpty { "Channel ${channels.size + 1}" }
                
                val kodiMetadata = buildMap {
                    for (match in kodiMatches) {
                        val key = match.groups[1]!!.value
                        val value = match.groups[2]?.value?.ifBlank { null } ?: continue
                        put(key.trim(), value.trim())
                    }
                }
                
                channels.add(
                    M3UChannel(
                        id = "${currentLine.hashCode()}_${channels.size}",
                        name = finalTitle,
                        url = currentLine,
                        group = metadata[M3U_GROUP_TITLE_MARK].orEmpty(),
                        logoUrl = metadata[M3U_TVG_LOGO_MARK].orEmpty(),
                        relationId = relationId,
                        licenseType = kodiMetadata[KODI_LICENSE_TYPE],
                        licenseKey = kodiMetadata[KODI_LICENSE_KEY]
                    )
                )
                
                metadata = emptyMap()
                title = ""
                kodiMatches.clear()
            }
        }
        
        return channels to epgUrl
    }
    
    private fun parseMetadata(text: String): Map<String, String> = buildMap {
        val matches = metadataRegex.findAll(text)
        for (match in matches) {
            val key = match.groups[1]!!.value
            val value = match.groups[2]?.value?.ifBlank { null } ?: continue
            put(key.trim(), value.trim())
        }
    }
    
    private fun extractEpgUrl(line: String): String? {
        val xTvgUrlMatch = Regex("""x-tvg-url\s*=\s*"([^"]+)"""").find(line)
        val urlTvgMatch = Regex("""url-tvg\s*=\s*"([^"]+)"""").find(line)
        
        return xTvgUrlMatch?.groupValues?.get(1) ?: urlTvgMatch?.groupValues?.get(1)
    }
}
