package com.antoniegil.astronia.util.parser

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class EpgProgram(
    val title: String,
    val startTime: Long,
    val stopTime: Long
)

object EpgParser {
    
    fun parse(input: InputStream): Map<String, List<EpgProgram>> {
        val result = mutableMapOf<String, MutableList<EpgProgram>>()
        
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        
        with(parser) {
            while (name != "tv" && eventType != XmlPullParser.END_DOCUMENT) next()
            
            while (next() != XmlPullParser.END_TAG) {
                if (eventType != XmlPullParser.START_TAG) continue
                
                when (name) {
                    "programme" -> {
                        val programme = readProgramme()
                        if (programme != null) {
                            result.getOrPut(programme.first) { mutableListOf() }.add(programme.second)
                        }
                    }
                    else -> skip()
                }
            }
        }
        
        return result
    }
    
    private fun XmlPullParser.readProgramme(): Pair<String, EpgProgram>? {
        require(XmlPullParser.START_TAG, null, "programme")
        
        val start = getAttributeValue(null, "start")
        val stop = getAttributeValue(null, "stop")
        val channel = getAttributeValue(null, "channel")
        
        var title: String? = null
        
        while (next() != XmlPullParser.END_TAG) {
            if (eventType != XmlPullParser.START_TAG) continue
            when (name) {
                "title" -> title = readTitle()
                else -> skip()
            }
        }
        
        require(XmlPullParser.END_TAG, null, "programme")
        
        if (start == null || stop == null || channel == null || title == null) {
            return null
        }
        
        val startTime = parseEpgTime(start)
        val stopTime = parseEpgTime(stop)
        
        return channel to EpgProgram(title, startTime, stopTime)
    }
    
    private fun XmlPullParser.readTitle(): String? = optional {
        require(XmlPullParser.START_TAG, null, "title")
        val title = readText()
        require(XmlPullParser.END_TAG, null, "title")
        return title
    }
    
    private fun XmlPullParser.readText(): String {
        var result = ""
        if (next() == XmlPullParser.TEXT) {
            result = text
            nextTag()
        }
        return result
    }
    
    private fun XmlPullParser.skip() {
        check(eventType == XmlPullParser.START_TAG)
        var depth = 1
        while (depth != 0) {
            when (next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }
    
    private inline fun optional(block: () -> String): String? =
        runCatching { block() }.getOrNull()
    
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
}
