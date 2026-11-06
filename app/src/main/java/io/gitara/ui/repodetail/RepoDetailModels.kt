package io.gitara.ui.repodetail

import android.content.Context
import android.widget.ListView
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

interface RepoDetailHost {
    fun onNavigateUp()
    fun onChooseBranch()
    fun onTabSelected(index: Int)
    fun onCommitSearch(query: String?)
    fun onFragmentContainerReady()
    fun createOperationsList(context: Context): ListView
}

@Stable
class RepoDetailController {
    var selectedTab by mutableIntStateOf(0)
        private set

    var refreshTick by mutableIntStateOf(0)
        private set

    var operationsOpen by mutableStateOf(false)
        private set

    var searching by mutableStateOf(false)
        private set

    var query by mutableStateOf("")
        private set

    var progressVisible by mutableStateOf(false)
        private set

    var progressMessage by mutableStateOf("")
        private set

    var progressLeft by mutableStateOf("")
        private set

    var progressRight by mutableStateOf("")
        private set

    var progressValue by mutableIntStateOf(0)
        private set

    fun selectTab(index: Int) {
        selectedTab = index
    }

    fun refresh() {
        refreshTick++
    }

    fun openOperations() {
        operationsOpen = true
    }

    fun closeOperations() {
        operationsOpen = false
    }

    fun openSearch() {
        searching = true
    }

    fun closeSearch() {
        searching = false
        query = ""
    }

    fun updateQuery(value: String) {
        query = value
    }

    fun showProgress(message: String, left: String, right: String) {
        progressMessage = message
        progressLeft = left
        progressRight = right
        progressValue = 0
        progressVisible = true
    }

    fun updateProgress(message: String, left: String, right: String, value: Int) {
        progressMessage = message
        progressLeft = left
        progressRight = right
        progressValue = value
    }

    fun hideProgress() {
        progressVisible = false
    }

    fun consumeBack(): Boolean {
        if (operationsOpen) {
            operationsOpen = false
            return true
        }
        if (searching) {
            closeSearch()
            return true
        }
        return false
    }
}