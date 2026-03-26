package com.antoniegil.astronia.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

data class M3U8Channel(
    val id: String,
    val name: String,
    val url: String,
    val group: String = "",
    val country: String = "",
    val language: String = "",
    val logoUrl: String = "",
    val tvgId: String = "",
    val tvgName: String = "",
    val epgTitle: String = "",
    val epgPrograms: List<EpgProgram> = emptyList()
)

data class EpgProgram(
    val title: String,
    val startTime: Long,
    val stopTime: Long
)

object M3U8Parser {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val client by lazy {
        NetworkUtils.createHttpClient(
            connectTimeoutMs = PlayerConstants.M3U8_CONNECTION_TIMEOUT_MS.toLong(),
            readTimeoutMs = PlayerConstants.M3U8_READ_TIMEOUT_MS.toLong(),
            trustAllCerts = true
        )
    }
    
    private var epgUrl: String? = null
    private var epgData: Map<String, List<EpgProgram>> = emptyMap()
    private var onEpgLoadedCallback: ((List<M3U8Channel>) -> Unit)? = null
    private var cachedChannels: List<M3U8Channel> = emptyList()
    private var epgJob: kotlinx.coroutines.Job? = null
    
    suspend fun parseM3U8(content: String): Result<List<M3U8Channel>> = withContext(Dispatchers.IO) {
        try {
            extractEpgUrl(content)
            
            val channels = mutableListOf<M3U8Channel>()
            val lines = content.lineSequence()
            
            var currentName = ""
            var currentGroup = ""
            var currentCountry = ""
            var currentLanguage = ""
            var currentLogoUrl = ""
            var currentTvgId = ""
            var currentTvgName = ""
            
            for (line in lines) {
                val trimmedLine = line.trim()
                
                if (trimmedLine.isEmpty()) continue
                
                if (trimmedLine.startsWith("#EXTINF:")) {
                    currentName = extractChannelName(trimmedLine)
                    currentGroup = extractGroup(trimmedLine)
                    currentCountry = extractCountry(trimmedLine)
                    currentLanguage = extractLanguage(trimmedLine)
                    currentLogoUrl = extractLogoUrl(trimmedLine)
                    currentTvgId = extractTvgId(trimmedLine)
                    currentTvgName = extractTvgName(trimmedLine)
                } else if (!trimmedLine.startsWith("#")) {
                    if (trimmedLine.startsWith("http") || trimmedLine.startsWith("rtmp") || 
                        trimmedLine.startsWith("rtsp") || trimmedLine.startsWith("udp")) {
                        val name = currentName.ifEmpty { "Channel ${channels.size + 1}" }
                        val finalId = "${trimmedLine.hashCode()}_${channels.size}"
                        
                        channels.add(
                            M3U8Channel(
                                id = finalId,
                                name = name,
                                url = trimmedLine,
                                group = currentGroup,
                                country = currentCountry,
                                language = currentLanguage,
                                logoUrl = currentLogoUrl,
                                tvgId = currentTvgId,
                                tvgName = currentTvgName,
                                epgTitle = "",
                                epgPrograms = emptyList()
                            )
                        )
                    }
                    currentName = ""
                    currentGroup = ""
                    currentCountry = ""
                    currentLanguage = ""
                    currentLogoUrl = ""
                    currentTvgId = ""
                    currentTvgName = ""
                }
            }
            
            cachedChannels = channels
            epgJob?.cancel()
            epgJob = scope.launch {
                loadEpgData()
                val channelsWithEpg = attachEpgToChannels(channels)
                onEpgLoadedCallback?.invoke(channelsWithEpg)
            }
            
            Result.Success(channels)
        } catch (e: Exception) {
            Result.Error(e, "Failed to parse M3U8 content")
        }
    }
    
    private data class FetchResult(
        val channels: List<M3U8Channel>,
        val finalUrl: String
    )
    
    suspend fun parseM3U8FromUrl(url: String): Result<List<M3U8Channel>> = withContext(Dispatchers.IO) {
        val httpsUrl = NetworkUtils.convertToHttps(url)
        
        try {
            val result = fetchM3U8Channels(httpsUrl)
            if (result.channels.isEmpty()) {
                val singleChannel = listOf(
                    M3U8Channel(
                        id = "${result.finalUrl.hashCode()}_0",
                        name = result.finalUrl.substringAfterLast("/").substringBeforeLast(".").ifEmpty { 
                            result.finalUrl.substringAfterLast("/").substringBefore("?").ifEmpty { "Stream" }
                        },
                        url = result.finalUrl
                    )
                )
                return@withContext Result.Success(singleChannel)
            }
            
            cachedChannels = result.channels
            epgJob?.cancel()
            epgJob = scope.launch {
                loadEpgData()
                val channelsWithEpg = attachEpgToChannels(result.channels)
                onEpgLoadedCallback?.invoke(channelsWithEpg)
            }
            
            Result.Success(result.channels)
        } catch (e: Exception) {
            if (httpsUrl != url) {
                try {
                    val result = fetchM3U8Channels(url)
                    if (result.channels.isEmpty()) {
                        val singleChannel = listOf(
                            M3U8Channel(
                                id = "${result.finalUrl.hashCode()}_0",
                                name = result.finalUrl.substringAfterLast("/").substringBeforeLast(".").ifEmpty { 
                                    result.finalUrl.substringAfterLast("/").substringBefore("?").ifEmpty { "Stream" }
                                },
                                url = result.finalUrl
                            )
                        )
                        return@withContext Result.Success(singleChannel)
                    }
                    
                    cachedChannels = result.channels
                    epgJob?.cancel()
                    epgJob = scope.launch {
                        loadEpgData()
                        val channelsWithEpg = attachEpgToChannels(result.channels)
                        onEpgLoadedCallback?.invoke(channelsWithEpg)
                    }
                    
                    Result.Success(result.channels)
                } catch (fallbackException: Exception) {
                    Result.Error(fallbackException, "Failed to fetch M3U8 from URL: ${fallbackException.message}")
                }
            } else {
                Result.Error(e, "Failed to fetch M3U8 from URL: ${e.message}")
            }
        }
    }
    
    private fun fetchM3U8Channels(url: String): FetchResult {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}")
        }
        
        val finalUrl = response.request.url.toString()
        
        val contentLength = response.body.contentLength()
        if (contentLength > PlayerConstants.M3U8_MAX_SIZE_BYTES) {
            response.close()
            throw Exception("Content too large")
        }
        
        val channels = mutableListOf<M3U8Channel>()
        var currentName = ""
        var currentGroup = ""
        var currentCountry = ""
        var currentLanguage = ""
        var currentLogoUrl = ""
        var currentTvgId = ""
        var currentTvgName = ""
        var totalBytesRead = 0
        
        response.body.byteStream().bufferedReader().use { reader ->
            reader.lineSequence().forEach { line ->
                totalBytesRead += line.length + 1
                
                if (totalBytesRead > PlayerConstants.M3U8_MAX_SIZE_BYTES) {
                    return@forEach
                }
                
                val trimmedLine = line.trim()
                
                if (trimmedLine.isEmpty()) return@forEach
                
                if (trimmedLine.startsWith("#EXTM3U")) {
                    extractEpgUrl(trimmedLine)
                } else if (trimmedLine.startsWith("#EXTINF:")) {
                    currentName = extractChannelName(trimmedLine)
                    currentGroup = extractGroup(trimmedLine)
                    currentCountry = extractCountry(trimmedLine)
                    currentLanguage = extractLanguage(trimmedLine)
                    currentLogoUrl = extractLogoUrl(trimmedLine)
                    currentTvgId = extractTvgId(trimmedLine)
                    currentTvgName = extractTvgName(trimmedLine)
                } else if (!trimmedLine.startsWith("#")) {
                    if (trimmedLine.startsWith("http") || trimmedLine.startsWith("rtmp") || 
                        trimmedLine.startsWith("rtsp") || trimmedLine.startsWith("udp")) {
                        val name = currentName.ifEmpty { "Channel ${channels.size + 1}" }
                        val finalId = "${trimmedLine.hashCode()}_${channels.size}"
                        
                        channels.add(
                            M3U8Channel(
                                id = finalId,
                                name = name,
                                url = trimmedLine,
                                group = currentGroup,
                                country = currentCountry,
                                language = currentLanguage,
                                logoUrl = currentLogoUrl,
                                tvgId = currentTvgId,
                                tvgName = currentTvgName,
                                epgTitle = ""
                            )
                        )
                    }
                    currentName = ""
                    currentGroup = ""
                    currentCountry = ""
                    currentLanguage = ""
                    currentLogoUrl = ""
                    currentTvgId = ""
                    currentTvgName = ""
                }
            }
        }
        
        return FetchResult(channels, finalUrl)
    }
    
    private fun extractChannelName(line: String): String {
        val commaIndex = line.lastIndexOf(',')
        if (commaIndex != -1 && commaIndex < line.length - 1) {
            val name = line.substring(commaIndex + 1).trim()
            if (name.isNotEmpty()) return name
        }
        
        val tvgNameMatch = Regex("""tvg-name="([^"]+)"""").find(line)
        if (tvgNameMatch != null) {
            return tvgNameMatch.groupValues[1]
        }
        
        val tvgIdMatch = Regex("""tvg-id="([^"]+)"""").find(line)
        if (tvgIdMatch != null) {
            return tvgIdMatch.groupValues[1]
        }
        
        return ""
    }
    
    private fun extractGroup(line: String): String {
        val groupMatch = Regex("""group-title="([^"]+)"""").find(line)
        return groupMatch?.groupValues?.get(1) ?: ""
    }
    
    private fun extractCountry(line: String): String {
        val countryMatch = Regex("""tvg-country="([^"]+)"""").find(line)
        return countryMatch?.groupValues?.get(1) ?: ""
    }
    
    private fun extractLanguage(line: String): String {
        val languageMatch = Regex("""tvg-language="([^"]+)"""").find(line)
        return languageMatch?.groupValues?.get(1) ?: ""
    }
    
    private fun extractLogoUrl(line: String): String {
        val logoMatch = Regex("""tvg-logo="([^"]+)"""").find(line)
        return logoMatch?.groupValues?.get(1) ?: ""
    }
    
    private fun extractTvgId(line: String): String {
        val tvgIdMatch = Regex("""tvg-id="([^"]+)"""").find(line)
        return tvgIdMatch?.groupValues?.get(1) ?: ""
    }
    
    private fun extractTvgName(line: String): String {
        val tvgNameMatch = Regex("""tvg-name="([^"]+)"""").find(line)
        return tvgNameMatch?.groupValues?.get(1) ?: ""
    }
    
    private fun extractEpgUrl(line: String) {
        val xTvgUrlMatch = Regex("""x-tvg-url="([^"]+)"""").find(line)
        val urlTvgMatch = Regex("""url-tvg="([^"]+)"""").find(line)
        
        if (xTvgUrlMatch != null) {
            epgUrl = xTvgUrlMatch.groupValues[1]
        } else if (urlTvgMatch != null) {
            epgUrl = urlTvgMatch.groupValues[1]
        }
    }
    
    private suspend fun loadEpgData() {
        val url = epgUrl ?: return
        
        withContext(Dispatchers.IO) {
            try {
                val urls = url.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                
                for (singleUrl in urls) {
                    try {
                        val request = Request.Builder().url(singleUrl).build()
                        val response = client.newCall(request).execute()
                        
                        if (response.isSuccessful) {
                            val inputStream = if (singleUrl.endsWith(".gz")) {
                                java.util.zip.GZIPInputStream(response.body.byteStream())
                            } else {
                                response.body.byteStream()
                            }
                            val xmlContent = inputStream.bufferedReader().use { it.readText() }
                            epgData = parseEpgXml(xmlContent)
                            return@withContext
                        }
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
            }
        }
    }
    
    private fun attachEpgToChannels(channels: List<M3U8Channel>): List<M3U8Channel> {
        if (epgData.isEmpty()) {
            return channels
        }
        
        val currentTime = System.currentTimeMillis()
        return channels.map { channel ->
            val tvgId = channel.tvgId.ifEmpty { channel.tvgName }
            if (tvgId.isEmpty()) {
                return@map channel
            }
            
            val programs = epgData[tvgId] ?: epgData[tvgId.replace(".", "")] ?: emptyList()
            
            if (programs.isEmpty()) {
                return@map channel
            }
            
            val currentProgram = programs.find { currentTime in it.startTime..it.stopTime }
            val epgTitle = currentProgram?.title ?: ""
            
            channel.copy(
                epgTitle = epgTitle,
                epgPrograms = programs
            )
        }
    }
    
    private fun parseEpgXml(xml: String): Map<String, List<EpgProgram>> {
        val result = mutableMapOf<String, MutableList<EpgProgram>>()
        val currentTime = System.currentTimeMillis()
        
        val programmeRegex = Regex("""<programme\s+start="([^"]+)"\s+stop="([^"]+)"\s+channel="([^"]+)"[^>]*>(.*?)</programme>""", RegexOption.DOT_MATCHES_ALL)
        val titleRegex = Regex("""<title[^>]*>([^<]+)</title>""")
        
        programmeRegex.findAll(xml).forEach { match ->
            val startTimeStr = match.groupValues[1]
            val stopTimeStr = match.groupValues[2]
            val channelId = match.groupValues[3]
            val content = match.groupValues[4]
            
            val startTime = parseEpgTime(startTimeStr)
            val stopTime = parseEpgTime(stopTimeStr)
            
            if (stopTime > currentTime) {
                val titleMatch = titleRegex.find(content)
                if (titleMatch != null) {
                    val title = titleMatch.groupValues[1]
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace("&apos;", "'")
                    val program = EpgProgram(title, startTime, stopTime)
                    
                    val normalizedId = channelId.replace(".", "")
                    result.getOrPut(channelId) { mutableListOf() }.add(program)
                    if (channelId != normalizedId) {
                        result.getOrPut(normalizedId) { mutableListOf() }.add(program)
                    }
                }
            }
        }
        
        result.values.forEach { it.sortBy { p -> p.startTime } }
        
        return result
    }
    
    private fun parseEpgTime(timeStr: String): Long {
        try {
            val year = timeStr.take(4).toInt()
            val month = timeStr.substring(4, 6).toInt()
            val day = timeStr.substring(6, 8).toInt()
            val hour = timeStr.substring(8, 10).toInt()
            val minute = timeStr.substring(10, 12).toInt()
            val second = timeStr.substring(12, 14).toInt()
            
            val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            calendar.set(year, month - 1, day, hour, minute, second)
            return calendar.timeInMillis
        } catch (e: Exception) {
            return 0L
        }
    }
    
    fun setEpgLoadedCallback(callback: (List<M3U8Channel>) -> Unit) {
        onEpgLoadedCallback = callback
    }
    
    fun clearEpgLoadedCallback() {
        onEpgLoadedCallback = null
        epgJob?.cancel()
        epgJob = null
    }
}
