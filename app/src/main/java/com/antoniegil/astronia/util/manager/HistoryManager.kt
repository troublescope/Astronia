package com.antoniegil.astronia.util.manager

import android.content.Context
import android.content.res.Resources
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.antoniegil.astronia.R
import kotlinx.coroutines.flow.StateFlow

object HistoryManager {
    
    val historyFlow: StateFlow<List<HistoryItem>>
        get() = SettingsManager.historyFlow
    
    fun getHistory(context: Context): List<HistoryItem> {
        return SettingsManager.getInstance(context).getHistory()
    }
    
    fun addOrUpdateHistory(context: Context, url: String, name: String, lastChannelUrl: String? = null, lastChannelId: String? = null, logoUrl: String = "") {
        SettingsManager.getInstance(context).addOrUpdateHistory(url, name, lastChannelUrl, lastChannelId, logoUrl)
    }
    
    fun deleteHistoryItem(context: Context, item: HistoryItem) {
        SettingsManager.getInstance(context).deleteHistoryItem(item)
    }
    
    suspend fun deleteHistoryItemWithUndo(
        context: Context,
        item: HistoryItem,
        snackbarHostState: SnackbarHostState,
        resources: Resources
    ) {
        deleteHistoryItem(context, item)
        val result = snackbarHostState.showSnackbar(
            message = resources.getString(R.string.item_deleted),
            actionLabel = resources.getString(R.string.undo),
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            addOrUpdateHistory(
                context,
                item.url,
                item.name,
                item.lastChannelUrl,
                item.lastChannelId,
                item.logoUrl
            )
        }
    }
}
