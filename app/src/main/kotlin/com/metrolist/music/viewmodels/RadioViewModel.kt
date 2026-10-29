/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.models.RadioStation
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject

/**
 * ViewModel for the Radio Browser screen.
 * Uses the Radio Browser API (api.radio-browser.info) – the same community-maintained
 * database that RadioDroid references.
 */
@HiltViewModel
class RadioViewModel @Inject constructor() : ViewModel() {

    // Radio Browser community DNS round-robin base URL
    private val baseUrl = "https://de1.api.radio-browser.info/json"

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    private val _stations = MutableStateFlow<List<RadioStation>>(emptyList())
    val stations = _stations.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _searchMode = MutableStateFlow(RadioSearchMode.NAME)
    val searchMode = _searchMode.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Load top stations on first open
        loadTopStations()
    }

    fun loadTopStations() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null
            try {
                val result: List<RadioStation> = httpClient.get("$baseUrl/stations/topvote/60") {
                    parameter("hidebroken", "true")
                    parameter("order", "votes")
                    parameter("reverse", "true")
                }.body()
                _stations.value = result
            } catch (e: Exception) {
                Timber.e(e, "RadioViewModel: Failed to load top stations")
                _error.value = "Failed to load stations: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onQueryChange(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            loadTopStations()
            return
        }
        // Debounce search
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400)
            performSearch(query, _searchMode.value)
        }
    }

    fun onSearchModeChange(mode: RadioSearchMode) {
        _searchMode.value = mode
        val q = _searchQuery.value
        if (q.isNotBlank()) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch(Dispatchers.IO) {
                performSearch(q, mode)
            }
        }
    }

    fun triggerSearch() {
        val q = _searchQuery.value
        if (q.isBlank()) {
            loadTopStations()
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            performSearch(q, _searchMode.value)
        }
    }

    private suspend fun performSearch(query: String, mode: RadioSearchMode) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            loadTopStations()
            return
        }
        _isLoading.value = true
        _error.value = null
        try {
            val result: List<RadioStation> = when (mode) {
                RadioSearchMode.NAME -> {
                    httpClient.get("$baseUrl/stations/search") {
                        parameter("name", cleanQuery)
                        parameter("hidebroken", "true")
                        parameter("order", "votes")
                        parameter("reverse", "true")
                        parameter("limit", "80")
                    }.body()
                }
                RadioSearchMode.CITY -> {
                    // Radio Browser stores cities, regions, and states in the 'state' field
                    val stateResults: List<RadioStation> = httpClient.get("$baseUrl/stations/search") {
                        parameter("state", cleanQuery)
                        parameter("hidebroken", "true")
                        parameter("order", "votes")
                        parameter("reverse", "true")
                        parameter("limit", "80")
                    }.body()

                    if (stateResults.isNotEmpty()) {
                        stateResults
                    } else {
                        // Fallback: search by tag (often cities like "london", "paris" are tagged)
                        httpClient.get("$baseUrl/stations/search") {
                            parameter("tag", cleanQuery)
                            parameter("hidebroken", "true")
                            parameter("order", "votes")
                            parameter("reverse", "true")
                            parameter("limit", "80")
                        }.body()
                    }
                }
                RadioSearchMode.COUNTRY -> {
                    val isCode = cleanQuery.length == 2 && cleanQuery.all { it.isLetter() }
                    httpClient.get("$baseUrl/stations/search") {
                        if (isCode) {
                            parameter("countrycode", cleanQuery.uppercase())
                        } else {
                            parameter("country", cleanQuery)
                        }
                        parameter("hidebroken", "true")
                        parameter("order", "votes")
                        parameter("reverse", "true")
                        parameter("limit", "80")
                    }.body()
                }
                RadioSearchMode.TAG -> {
                    httpClient.get("$baseUrl/stations/search") {
                        parameter("tag", cleanQuery)
                        parameter("hidebroken", "true")
                        parameter("order", "votes")
                        parameter("reverse", "true")
                        parameter("limit", "80")
                    }.body()
                }
            }
            _stations.value = result
        } catch (e: Exception) {
            Timber.e(e, "RadioViewModel: Search failed")
            _error.value = "Search failed: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }

    fun registerClick(stationUuid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                httpClient.get("$baseUrl/url/$stationUuid")
            } catch (e: Exception) {
                // Non-critical – just for vote tracking
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        httpClient.close()
    }
}

enum class RadioSearchMode {
    NAME,
    CITY,
    COUNTRY,
    TAG,
}
