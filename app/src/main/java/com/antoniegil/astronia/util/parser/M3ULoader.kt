package com.antoniegil.astronia.util.parser

import com.antoniegil.astronia.util.NetworkUtils
import com.antoniegil.astronia.util.common.PlayerConstants
import com.antoniegil.astronia.util.common.Result
import com.antoniegil.astronia.util.manager.EpgManager
import com.antoniegil.astronia.util.manager.toM3U8Channel
import kotlinx.coroutines.Dispatchers
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
    val relationId: String = "",
    val epgTitle: String = "",
    val epgPrograms: List<EpgProgram> = emptyList(),
    val licenseType: String? = null,
    val licenseKey: String? = null
) {
    companion object {
        const val LICENSE_TYPE_WIDEVINE = "com.widevine.alpha"
        const val LICENSE_TYPE_CLEAR_KEY = "clearkey"
        const val LICENSE_TYPE_CLEAR_KEY_2 = "org.w3.clearkey"
        const val LICENSE_TYPE_PLAY_READY = "com.microsoft.playready"
    }
}

object M3ULoader {
    
    private val client by lazy {
        NetworkUtils.createHttpClient(
            connectTimeoutMs = PlayerConstants.M3U8_CONNECTION_TIMEOUT_MS.toLong(),
            readTimeoutMs = PlayerConstants.M3U8_READ_TIMEOUT_MS.toLong(),
            trustAllCerts = true
        )
    }
    
    private var epgCallback: ((List<M3U8Channel>) -> Unit)? = null
    
    suspend fun parseM3U8(content: String): Result<List<M3U8Channel>> = withContext(Dispatchers.IO) {
        try {
            val (channels, epgUrl) = M3UParser.parse(content)
            val m3u8Channels = channels.map { it.toM3U8Channel() }
            
            if (epgUrl != null && channels.isNotEmpty()) {
                EpgManager.loadEpg(channels, epgUrl, client) { channelsWithEpg ->
                    val updatedChannels = channelsWithEpg.map { it.toM3U8Channel() }
                    epgCallback?.invoke(updatedChannels)
                }
            }
            
            Result.Success(m3u8Channels)
        } catch (e: Exception) {
            Result.Error(e, "Failed to parse M3U8 content")
        }
    }
    
    suspend fun parseM3U8FromUrl(url: String): Result<List<M3U8Channel>> = withContext(Dispatchers.IO) {
        val httpsUrl = NetworkUtils.convertToHttps(url)
        
        try {
            val content = fetchM3U8Content(httpsUrl)
            val result = parseM3U8(content)
            
            if (result is Result.Success && result.data.isEmpty()) {
                val singleChannel = M3U8Channel(
                    id = "${httpsUrl.hashCode()}_0",
                    name = httpsUrl.substringAfterLast("/").substringBeforeLast(".").ifEmpty {
                        httpsUrl.substringAfterLast("/").substringBefore("?").ifEmpty { "Stream" }
                    },
                    url = httpsUrl
                )
                return@withContext Result.Success(listOf(singleChannel))
            }
            
            return@withContext result
        } catch (e: Exception) {
            if (httpsUrl != url) {
                try {
                    val content = fetchM3U8Content(url)
                    val result = parseM3U8(content)
                    
                    if (result is Result.Success && result.data.isEmpty()) {
                        val singleChannel = M3U8Channel(
                            id = "${url.hashCode()}_0",
                            name = url.substringAfterLast("/").substringBeforeLast(".").ifEmpty {
                                url.substringAfterLast("/").substringBefore("?").ifEmpty { "Stream" }
                            },
                            url = url
                        )
                        return@withContext Result.Success(listOf(singleChannel))
                    }
                    
                    return@withContext result
                } catch (fallbackException: Exception) {
                    Result.Error(fallbackException, "Failed to fetch M3U8 from URL: ${fallbackException.message}")
                }
            } else {
                Result.Error(e, "Failed to fetch M3U8 from URL: ${e.message}")
            }
        }
    }
    
    private fun fetchM3U8Content(url: String): String {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}")
        }
        
        val contentLength = response.body.contentLength()
        if (contentLength > PlayerConstants.M3U8_MAX_SIZE_BYTES) {
            response.close()
            throw Exception("Content too large")
        }
        
        return response.body.string()
    }
    
    fun setEpgLoadedCallback(callback: (List<M3U8Channel>) -> Unit) {
        epgCallback = callback
    }
    
    fun clearEpgLoadedCallback() {
        epgCallback = null
        EpgManager.clear()
    }
    
    private fun M3UChannel.toM3U8Channel() = M3U8Channel(
        id = id,
        name = name,
        url = url,
        group = group,
        logoUrl = logoUrl,
        relationId = relationId,
        licenseType = licenseType,
        licenseKey = licenseKey
    )
}
