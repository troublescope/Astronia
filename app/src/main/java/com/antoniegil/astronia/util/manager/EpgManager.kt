package com.antoniegil.astronia.util.manager

import com.antoniegil.astronia.util.parser.EpgParser
import com.antoniegil.astronia.util.parser.EpgProgram
import com.antoniegil.astronia.util.parser.M3U8Channel
import com.antoniegil.astronia.util.parser.M3UChannel
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.zip.GZIPInputStream

data class ChannelWithEpg(
    val channel: M3UChannel,
    val epgTitle: String = "",
    val epgPrograms: List<EpgProgram> = emptyList()
)

object EpgManager {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mmkv = MMKV.mmkvWithID("epg_cache")
    private val indexMmkv = MMKV.mmkvWithID("epg_index")
    private val json = Json { ignoreUnknownKeys = true }
    private var epgDataBySource: Map<String, Map<String, List<EpgProgram>>> = emptyMap()
    private var onEpgLoadedCallback: ((List<ChannelWithEpg>) -> Unit)? = null
    private var cachedChannels: List<M3UChannel> = emptyList()
    private var epgJob: kotlinx.coroutines.Job? = null
    
    private val _refreshingEpgUrls = MutableStateFlow<List<String>>(emptyList())

    private const val CACHE_EXPIRY_MS = 7L * 24 * 60 * 60 * 1000
    private const val PAGE_SIZE = 50
    
    fun loadEpg(
        channels: List<M3UChannel>,
        epgUrl: String?,
        client: OkHttpClient,
        callback: (List<ChannelWithEpg>) -> Unit
    ) {
        cachedChannels = channels
        onEpgLoadedCallback = callback
        
        epgJob?.cancel()
        epgJob = scope.launch {
            cleanExpiredCache()
            loadCachedEpgData()
            downloadAndParseEpg(epgUrl, client)
            val channelsWithEpg = attachEpgToChannels(channels)
            onEpgLoadedCallback?.invoke(channelsWithEpg)
        }
    }
    
    private suspend fun downloadAndParseEpg(epgUrl: String?, client: OkHttpClient) {
        if (epgUrl == null) return
        
        val urls = epgUrl.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val newEpgDataBySource = mutableMapOf<String, Map<String, List<EpgProgram>>>()
        
        withContext(Dispatchers.IO) {
            for (singleUrl in urls) {
                if (singleUrl in _refreshingEpgUrls.value) {
                    continue
                }
                
                if (isCacheValid(singleUrl)) {
                    continue
                }
                
                try {
                    _refreshingEpgUrls.value += singleUrl
                    
                    val request = Request.Builder().url(singleUrl).build()
                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val stream = response.body.byteStream()
                            val inputStream = if (singleUrl.endsWith(".gz", ignoreCase = true) ||
                                response.header("Content-Encoding")?.contains("gzip", ignoreCase = true) == true) {
                                GZIPInputStream(stream)
                            } else {
                                stream
                            }
                            
                            val channelMap = mutableMapOf<String, MutableList<EpgProgram>>()
                            EpgParser.parse(inputStream).collect { program ->
                                channelMap.getOrPut(program.channelId) { mutableListOf() }.add(program)
                            }
                            
                            val sortedData = channelMap.mapValues { entry ->
                                entry.value.distinctBy { it.startTime }.sortedBy { it.startTime }
                            }
                            
                            newEpgDataBySource[singleUrl] = sortedData
                            saveToCacheAsync(singleUrl, sortedData)
                            buildIndex(singleUrl, sortedData.keys)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    _refreshingEpgUrls.value -= singleUrl
                }
            }
        }
        
        epgDataBySource = epgDataBySource + newEpgDataBySource
    }
    
    private fun attachEpgToChannels(channels: List<M3UChannel>): List<ChannelWithEpg> {
        if (epgDataBySource.isEmpty()) {
            return channels.map { ChannelWithEpg(it) }
        }
        
        val currentTime = System.currentTimeMillis()
        
        return channels.map { channel ->
            val relationId = channel.relationId
            
            if (relationId.isEmpty()) {
                return@map ChannelWithEpg(channel)
            }
            
            val programs = findProgramsForChannel(relationId, currentTime)
            
            if (programs.isEmpty()) {
                return@map ChannelWithEpg(channel)
            }
            
            val currentProgram = programs.find { currentTime in it.startTime..it.stopTime }
            val epgTitle = currentProgram?.title ?: ""
            
            ChannelWithEpg(
                channel = channel,
                epgTitle = epgTitle,
                epgPrograms = programs
            )
        }
    }
    
    private fun findProgramsForChannel(relationId: String, currentTime: Long): List<EpgProgram> {
        val epgUrls = getEpgUrlsFromIndex(relationId)
        
        if (epgUrls.isNotEmpty()) {
            for (epgUrl in epgUrls) {
                val epgData = epgDataBySource[epgUrl] ?: continue
                val programs = epgData[relationId] ?: continue
                if (programs.any { currentTime in it.startTime..it.stopTime || it.stopTime > currentTime }) {
                    return programs
                }
            }
        } else {
            for ((_, epgData) in epgDataBySource) {
                val programs = epgData[relationId] ?: continue
                if (programs.any { currentTime in it.startTime..it.stopTime || it.stopTime > currentTime }) {
                    return programs
                }
            }
        }
        
        return emptyList()
    }

    private fun loadCachedEpgData() {
        val sourcesByUrl = mutableMapOf<String, Map<String, List<EpgProgram>>>()
        mmkv.allKeys()?.forEach { key ->
            if (key.startsWith("epg_data_")) {
                val url = key.removePrefix("epg_data_")
                mmkv.decodeString(key)?.let { jsonStr ->
                    try {
                        val data = json.decodeFromString<Map<String, List<EpgProgram>>>(jsonStr)
                        sourcesByUrl[url] = data
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        epgDataBySource = sourcesByUrl
    }
    
    private fun saveToCacheAsync(epgUrl: String, data: Map<String, List<EpgProgram>>) {
        try {
            val jsonStr = json.encodeToString(data)
            mmkv.encode("epg_data_$epgUrl", jsonStr)
            mmkv.encode("epg_time_$epgUrl", System.currentTimeMillis())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun isCacheValid(epgUrl: String): Boolean {
        val lastUpdate = mmkv.decodeLong("epg_time_$epgUrl", 0L)
        return lastUpdate > 0 && (System.currentTimeMillis() - lastUpdate) < CACHE_EXPIRY_MS
    }
    
    private fun buildIndex(epgUrl: String, channelIds: Set<String>) {
        channelIds.forEach { channelId ->
            val existingUrls = indexMmkv.decodeString("idx_$channelId")?.split(",")?.toMutableSet() ?: mutableSetOf()
            existingUrls.add(epgUrl)
            indexMmkv.encode("idx_$channelId", existingUrls.joinToString(","))
        }
    }
    
    private fun getEpgUrlsFromIndex(relationId: String): List<String> {
        return indexMmkv.decodeString("idx_$relationId")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
    }
    
    private fun cleanExpiredCache() {
        val currentTime = System.currentTimeMillis()
        val expiredUrls = mutableSetOf<String>()
        
        mmkv.allKeys()?.forEach { key ->
            if (key.startsWith("epg_time_")) {
                val timestamp = mmkv.decodeLong(key, 0L)
                if (currentTime - timestamp > CACHE_EXPIRY_MS) {
                    val url = key.removePrefix("epg_time_")
                    mmkv.remove("epg_data_$url")
                    mmkv.remove(key)
                    expiredUrls.add(url)
                }
            }
        }
        
        if (expiredUrls.isNotEmpty()) {
            indexMmkv.allKeys()?.forEach { key ->
                if (key.startsWith("idx_")) {
                    val urls = indexMmkv.decodeString(key)?.split(",")?.toMutableSet() ?: return@forEach
                    if (urls.removeAll(expiredUrls)) {
                        if (urls.isEmpty()) {
                            indexMmkv.remove(key)
                        } else {
                            indexMmkv.encode(key, urls.joinToString(","))
                        }
                    }
                }
            }
        }
    }
    
    fun clear() {
        onEpgLoadedCallback = null
        epgJob?.cancel()
        epgJob = null
        epgDataBySource = emptyMap()
        _refreshingEpgUrls.value = emptyList()
    }
}

fun ChannelWithEpg.toM3U8Channel() = M3U8Channel(
    id = channel.id,
    name = channel.name,
    url = channel.url,
    group = channel.group,
    logoUrl = channel.logoUrl,
    relationId = channel.relationId,
    epgTitle = epgTitle,
    epgPrograms = epgPrograms
)
