package com.example.parkingapp.ui.maps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.example.parkingapp.R
import com.example.parkingapp.databinding.FragmentMapsBinding
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.CircularBounds
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.api.net.SearchNearbyRequest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.installations.FirebaseInstallations
import kotlin.math.exp
import kotlin.math.ln

class DashboardFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentMapsBinding? = null
    private val binding get() = _binding!!
    private lateinit var map: GoogleMap
    private lateinit var placesClient: PlacesClient
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    data class ParkingTag(
        val placeId: String?,
        val mapsUri: String?,
        val position: LatLng
    )

    enum class ParkingStatus { GREEN, YELLOW, RED }
    private val HALF_LIFE_MIN = 120.0
    private val MAX_VOTES = 200

    private fun statusLabel(s: ParkingStatus) = when (s) {
        ParkingStatus.GREEN -> "Dużo wolnych miejsc"
        ParkingStatus.YELLOW -> "Średnie obłożenie"
        ParkingStatus.RED -> "Prawie pełny"
    }

    private fun statusDot(s: ParkingStatus) = when (s) {
        ParkingStatus.GREEN -> "\uD83D\uDFE2"
        ParkingStatus.YELLOW -> "\uD83D\uDFE1"
        ParkingStatus.RED -> "\uD83D\uDD34"
    }

    private fun weightFor(ageMillis: Long): Double {
        val ageMin = ageMillis / 60000.0
        val k = ln(2.0) / HALF_LIFE_MIN
        return exp(-k * ageMin)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
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
            val tag = marker.tag as? ParkingTag
            val intent = if (!tag?.mapsUri.isNullOrBlank()) {
                Intent(Intent.ACTION_VIEW, Uri.parse(tag!!.mapsUri))
            } else {
                val lat = marker.position.latitude
                val lng = marker.position.longitude
                val q = Uri.encode(marker.title ?: "Parking")
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$q"))
            }
            try {
                intent.setPackage("com.google.android.apps.maps")
                startActivity(intent)
            } catch (_: Exception) {
                intent.setPackage(null)
                try { startActivity(intent) } catch (_: Exception) { }
            }
        }

        map.setOnMarkerClickListener { marker ->
            val placeId = (marker.tag as? ParkingTag)?.placeId
            if (placeId != null) {
                updateMarkerStatusFromFirestore(placeId, marker)
            } else {
                marker.snippet = "Brak danych – przytrzymaj, aby zgłosić"
            }
            marker.showInfoWindow()
            true
        }

        map.setOnInfoWindowLongClickListener { marker ->
            val tag = marker.tag as? ParkingTag ?: return@setOnInfoWindowLongClickListener
            val placeId = tag.placeId ?: return@setOnInfoWindowLongClickListener
            val latLng = tag.position
            requireProximity(latLng, 150f) { allowed ->
                if (allowed) {
                    showReportDialog(placeId) {
                        updateMarkerStatusFromFirestore(placeId, marker)
                    }
                }
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
        val request = SearchNearbyRequest.builder(circle, fields)
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
                            .snippet("Ładowanie statusu…")
                    )

                    marker?.tag = ParkingTag(
                        placeId = place.id,
                        mapsUri = place.googleMapsUri?.toString(),
                        position = latLng
                    )

                    if (marker != null && place.id != null) {
                        updateMarkerStatusFromFirestore(place.id!!, marker)
                    }
                }
            }
            .addOnFailureListener {
                map.addMarker(MarkerOptions().position(center).title("Katowice"))
            }
    }

    private fun updateMarkerStatusFromFirestore(placeId: String, marker: Marker) {
        db.collection("parking_votes")
            .document(placeId)
            .collection("votes")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(MAX_VOTES.toLong())
            .get()
            .addOnSuccessListener { snap ->
                var sumG = 0.0;
                var sumY = 0.0;
                var sumR = 0.0
                var newestMillis = 0L
                var newestStatus: ParkingStatus? = null
                val now = System.currentTimeMillis()

                snap.forEach { doc ->
                    val ts = doc.getTimestamp("timestamp")?.toDate()?.time ?: return@forEach
                    val st = when (doc.getString("status")) {
                        "GREEN" -> ParkingStatus.GREEN
                        "YELLOW" -> ParkingStatus.YELLOW
                        "RED" -> ParkingStatus.RED
                        else -> null
                    } ?: return@forEach

                    val w = weightFor(now - ts)

                    when (st) {
                        ParkingStatus.GREEN -> sumG += w
                        ParkingStatus.YELLOW -> sumY += w
                        ParkingStatus.RED -> sumR += w
                    }

                    if (ts > newestMillis) {
                        newestMillis = ts
                        newestStatus = st
                    }
                }

                val best = when {
                    sumG > sumY && sumG > sumR -> ParkingStatus.GREEN
                    sumY > sumG && sumY > sumR -> ParkingStatus.YELLOW
                    sumR > sumG && sumR > sumY -> ParkingStatus.RED
                    else -> newestStatus
                }

                marker.snippet = if (best != null && (sumG + sumY + sumR) > 0.0) {
                    "${statusDot(best)}  ${statusLabel(best)}"
                } else {
                    "Brak danych – przytrzymaj, aby zgłosić"
                }
                if (marker.isInfoWindowShown) marker.showInfoWindow()
            }
            .addOnFailureListener {
                marker.snippet = "Nie udało się pobrać statusu"
                if (marker.isInfoWindowShown) marker.showInfoWindow()
            }
    }

    private fun showReportDialog(placeId: String, onReported: () -> Unit) {
        val options = arrayOf(
            "${statusDot(ParkingStatus.GREEN)}  ${statusLabel(ParkingStatus.GREEN)}",
            "${statusDot(ParkingStatus.YELLOW)}  ${statusLabel(ParkingStatus.YELLOW)}",
            "${statusDot(ParkingStatus.RED)}  ${statusLabel(ParkingStatus.RED)}"
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Zgłoś obłożenie parkingu")
            .setItems(options) { _, which ->
                val chosen = when (which) {
                    0 -> ParkingStatus.GREEN
                    1 -> ParkingStatus.YELLOW
                    else -> ParkingStatus.RED
                }
                submitReport(placeId, chosen, onReported)
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun getUserKey(onReady: (String) -> Unit) {
        FirebaseInstallations.getInstance().id
            .addOnSuccessListener { onReady(it) }
            .addOnFailureListener { onReady("anon") }
    }

    private fun submitReport(placeId: String, status: ParkingStatus, onReported: () -> Unit) {
        getUserKey { userKey ->
            val vote = hashMapOf(
                "status" to status.name,
                "timestamp" to Timestamp.now()
            )
            db.collection("parking_votes")
                .document(placeId)
                .collection("votes")
                .document(userKey)
                .set(vote)
                .addOnSuccessListener { onReported() }
                .addOnFailureListener { }
        }
    }

    private fun requireProximity(parkingLatLng: LatLng, allowedMeters: Float, result: (Boolean) -> Unit) {
        val ctx = requireContext()
        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            result(true)
            return
        }
        fused.lastLocation.addOnSuccessListener { loc: Location? ->
            if (loc == null) { result(false); return@addOnSuccessListener }
            val out = FloatArray(1)
            Location.distanceBetween(
                loc.latitude, loc.longitude,
                parkingLatLng.latitude, parkingLatLng.longitude,
                out
            )
            result(out[0] <= allowedMeters)
        }.addOnFailureListener { result(false) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
