package com.example.parkingapp.ui.list

import com.google.android.gms.maps.model.LatLng

data class ParkingRow(
    val placeId: String,
    val name: String,
    val position: LatLng,
    val mapsUri: String?,
    val distanceMeters: Int,
    val statusDot: String,
    val statusLabel: String
)
