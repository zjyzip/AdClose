package com.close.hook.ads.ui.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppsPagerViewModel:ViewModel() {

    val query = MutableLiveData("")
    private var searchJob: Job? = null


    fun postQuery(text: String, delayTime: Long = 300L) {
        searchJob?.cancel()
        if (delayTime == 0L)
            query.postValue(text)
        else {
            searchJob = viewModelScope.launch {
                delay(delayTime)
                query.postValue(text)
            }
        }
    }

}