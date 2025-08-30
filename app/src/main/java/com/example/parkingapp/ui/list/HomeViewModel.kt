package com.example.parkingapp.ui.list

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class HomeViewModel : ViewModel() {
    private val _items = MutableLiveData<List<ParkingRow>>(emptyList())
    val items: LiveData<List<ParkingRow>> = _items

    fun setItems(list: List<ParkingRow>) {
        _items.value = list
    }
}
