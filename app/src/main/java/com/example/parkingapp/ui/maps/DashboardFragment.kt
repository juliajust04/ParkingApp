package com.example.parkingapp.ui.maps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.parkingapp.R
import com.example.parkingapp.databinding.FragmentMapsBinding
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchNearbyRequest

class DashboardFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentMapsBinding? = null
    private val binding get() = _binding!!

    private lateinit var map: GoogleMap
    private lateinit var placesClient: PlacesClient

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!Places.isInitialized()) {
            Places.initialize(requireContext(), getString(R.string.google_maps_key))
        }
        placesClient = Places.createClient(requireContext())

        val mapFragment = childFragmentManager
            .findFragmentById(R.id.map_fragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        map.uiSettings.apply {
            isZoomControlsEnabled = true
            isZoomGesturesEnabled = true
            isScrollGesturesEnabled = true
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
        }

        val katowice = LatLng(50.2648919, 19.0237815)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(katowice, 13f))

        map.setOnInfoWindowClickListener { marker ->
            (marker.tag as? String)?.let { uri ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                intent.setPackage("com.google.android.apps.maps")
                startActivity(intent)
            }
        }

        loadParkingMarkers(katowice)
    }

    private fun loadParkingMarkers(center: LatLng) {
        val fields = listOf(
            Place.Field.ID,
            Place.Field.DISPLAY_NAME,
            Place.Field.LOCATION,
            Place.Field.GOOGLE_MAPS_URI
        )

        val circle = CircularBounds.newInstance(center, 3500.0)

        val request = SearchNearbyRequest
            .builder(circle, fields)
            .setIncludedTypes(listOf("parking"))
            .setMaxResultCount(20)
            .setRankPreference(SearchNearbyRequest.RankPreference.DISTANCE)
            .build()

        placesClient.searchNearby(request)
            .addOnSuccessListener { resp ->
                map.clear()
                resp.places.forEach { place ->
                    val latLng = place.location ?: return@forEach
                    val title = place.displayName ?: "Parking"
                    val marker = map.addMarker(
                        MarkerOptions()
                            .position(latLng)
                            .title(title)
                            .snippet("Kliknij, aby otworzyć w Mapach Google")
                    )
                    marker?.tag = place.googleMapsUri?.toString()
                }
            }
            .addOnFailureListener { err ->
                map.addMarker(MarkerOptions().position(center).title("Katowice"))
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
