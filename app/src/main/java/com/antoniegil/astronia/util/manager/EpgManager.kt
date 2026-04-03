package com.antoniegil.astronia.util.manager

import com.antoniegil.astronia.util.parser.EpgParser
import com.antoniegil.astronia.util.parser.EpgProgram
import com.antoniegil.astronia.util.parser.M3U8Channel
import com.antoniegil.astronia.util.parser.M3UChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var epgData: Map<String, List<EpgProgram>> = emptyMap()
    private var onEpgLoadedCallback: ((List<ChannelWithEpg>) -> Unit)? = null
    private var cachedChannels: List<M3UChannel> = emptyList()
    private var epgJob: kotlinx.coroutines.Job? = null
    
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
            downloadAndParseEpg(epgUrl, client)
            val channelsWithEpg = attachEpgToChannels(channels)
            onEpgLoadedCallback?.invoke(channelsWithEpg)
        }
    }
    
    private suspend fun downloadAndParseEpg(epgUrl: String?, client: OkHttpClient) {
        if (epgUrl == null) return
        
        val urls = epgUrl.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val allEpgData = mutableMapOf<String, MutableList<EpgProgram>>()
        
        withContext(Dispatchers.IO) {
            for (singleUrl in urls) {
                try {
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
                            
                            val parsed = EpgParser.parse(inputStream)
                            parsed.forEach { (channelId, programs) ->
                                allEpgData.getOrPut(channelId) { mutableListOf() }.addAll(programs)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        
        epgData = allEpgData.mapValues { entry ->
            entry.value.distinctBy { it.startTime }.sortedBy { it.startTime }
        }
    }
    
    private fun attachEpgToChannels(channels: List<M3UChannel>): List<ChannelWithEpg> {
        if (epgData.isEmpty()) {
            return channels.map { ChannelWithEpg(it) }
        }
        
        val currentTime = System.currentTimeMillis()
        
        return channels.map { channel ->
            val relationId = channel.relationId
            
            if (relationId.isEmpty()) {
                return@map ChannelWithEpg(channel)
            }
            
            val programs = epgData[relationId] ?: emptyList()
            
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
    
    fun clear() {
        onEpgLoadedCallback = null
        epgJob?.cancel()
        epgJob = null
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
