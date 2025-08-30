package com.example.parkingapp.ui.list

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.parkingapp.R
import com.example.parkingapp.databinding.FragmentListBinding
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
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
import kotlin.math.roundToInt

class HomeFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private val vm: HomeViewModel by viewModels()
    private lateinit var places: PlacesClient
    private val db by lazy { FirebaseFirestore.getInstance() }

    private companion object {
        const val TAG = "List"
        const val HALF_LIFE_MIN = 120.0
        const val MAX_VOTES = 200L
        const val NEARBY_RADIUS_METERS = 5000.0
        const val NEARBY_LIMIT = 20
        val KATOWICE_CENTER = LatLng(50.2648919, 19.0237815)
    }

    enum class ParkingStatus {GREEN, YELLOW, RED }

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

    private fun distanceMeters(a: LatLng, b: LatLng): Int {
        val out = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
        return out[0].roundToInt()
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!Places.isInitialized()) {
            Places.initialize(requireContext(), getString(R.string.google_maps_key))
        }
        places = Places.createClient(requireContext())

        val adapter = ParkingAdapter(
            onClick = { openInMaps(it) },
            onLongClick = { showReportDialog(it.placeId) { reload() } }
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.swipe.setOnRefreshListener { reload() }

        vm.items.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            binding.swipe.isRefreshing = false
        }

        binding.swipe.isRefreshing = true
        reload()
    }

    private fun reload() {
        val fused = LocationServices.getFusedLocationProviderClient(requireContext())
        val fine = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            loadAround(KATOWICE_CENTER, userLoc = null)
            return
        }

        fused.lastLocation
            .addOnSuccessListener { loc ->
                val user = loc?.let { LatLng(it.latitude, it.longitude) } ?: KATOWICE_CENTER
                loadAround(user, userLoc = user)
            }
            .addOnFailureListener {
                loadAround(KATOWICE_CENTER, userLoc = null)
            }
    }

    private fun loadAround(center: LatLng, userLoc: LatLng?) {
        val fields = listOf(
            Place.Field.ID,
            Place.Field.DISPLAY_NAME,
            Place.Field.LOCATION,
            Place.Field.GOOGLE_MAPS_URI
        )

        val req = SearchNearbyRequest.builder(
            CircularBounds.newInstance(center, NEARBY_RADIUS_METERS),
            fields
        )
            .setIncludedTypes(listOf("parking"))
            .setRankPreference(SearchNearbyRequest.RankPreference.DISTANCE)
            .setMaxResultCount(NEARBY_LIMIT)
            .build()

        places.searchNearby(req)
            .addOnSuccessListener { resp ->
                Log.d(TAG, "Nearby results: ${resp.places.size}")
                val base = resp.places.mapNotNull { p ->
                    val id = p.id ?: return@mapNotNull null
                    val pos = p.location ?: return@mapNotNull null
                    val name = p.displayName ?: "Parking"
                    val origin = userLoc ?: center
                    ParkingRow(
                        placeId = id,
                        name = name,
                        position = pos,
                        mapsUri = p.googleMapsUri?.toString(),
                        distanceMeters = distanceMeters(origin, pos),
                        statusDot = "⚪",
                        statusLabel = "Ładowanie…"
                    )
                }.sortedBy { it.distanceMeters }

                vm.setItems(base)
                if (base.isNotEmpty()) fetchStatusesAndUpdate(base) else binding.swipe.isRefreshing = false
            }
            .addOnFailureListener { e ->
                val msg = if (e is com.google.android.gms.common.api.ApiException) {
                    val code = e.statusCode
                    "Places error: $code (${com.google.android.gms.common.api.CommonStatusCodes.getStatusCodeString(code)}) ${e.message}"
                } else {
                    "Places error: ${e.javaClass.simpleName}: ${e.message}"
                }
                Log.e(TAG, msg)
                toast("Błąd pobierania parkingów")
                vm.setItems(emptyList())
                binding.swipe.isRefreshing = false
            }
    }

    private fun fetchStatusesAndUpdate(base: List<ParkingRow>) {
        val now = System.currentTimeMillis()

        val tasks = base.map { row ->
            db.collection("parking_votes")
                .document(row.placeId)
                .collection("votes")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .limit(MAX_VOTES)
                .get()
                .continueWith { task ->
                    var sumG = 0.0
                    var sumY = 0.0
                    var sumR = 0.0
                    var newestMillis = 0L
                    var newestStatus: ParkingStatus? = null

                    val snap = task.result ?: return@continueWith row.placeId to ("⚪" to "Brak danych – przytrzymaj, aby zgłosić")

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
                        if (ts > newestMillis) { newestMillis = ts; newestStatus = st }
                    }
                    val best = when {
                        sumG > sumY && sumG > sumR -> ParkingStatus.GREEN
                        sumY > sumG && sumY > sumR -> ParkingStatus.YELLOW
                        sumR > sumG && sumR > sumY -> ParkingStatus.RED
                        else -> newestStatus
                    }
                    val dot = when (best) {
                        ParkingStatus.GREEN -> "\uD83D\uDFE2"
                        ParkingStatus.YELLOW -> "\uD83D\uDFE1"
                        ParkingStatus.RED -> "\uD83D\uDD34"
                        else -> "⚪"
                    }
                    val label = when (best) {
                        ParkingStatus.GREEN -> "Dużo wolnych miejsc"
                        ParkingStatus.YELLOW -> "Średnie obłożenie"
                        ParkingStatus.RED -> "Prawie pełny"
                        else -> "Brak danych – przytrzymaj, aby zgłosić"
                    }
                    row.placeId to (dot to label)
                }
        }

        com.google.android.gms.tasks.Tasks.whenAllComplete(tasks)
            .addOnCompleteListener {
                val statusById = tasks.mapNotNull { t -> t.result }.associate { it.first to it.second }
                val updated = base.map { row ->
                    val pair = statusById[row.placeId]
                    if (pair != null) row.copy(statusDot = pair.first, statusLabel = pair.second) else row
                }
                vm.setItems(updated)
                binding.swipe.isRefreshing = false
            }
    }

    private fun openInMaps(row: ParkingRow) {
        val intent = if (!row.mapsUri.isNullOrBlank()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(row.mapsUri))
        } else {
            val q = Uri.encode(row.name)
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:${row.position.latitude},${row.position.longitude}?q=$q"))
        }
        try {
            intent.setPackage("com.google.android.apps.maps")
            startActivity(intent)
        } catch (_: Exception) {
            intent.setPackage(null)
            try { startActivity(intent) } catch (_: Exception) {}
        }
    }

    private fun showReportDialog(placeId: String, onReported: () -> Unit) {
        val options = arrayOf("\uD83D\uDFE2 Dużo wolnych miejsc", "\uD83D\uDFE1 Średnie obłożenie", "\uD83D\uDD34 Prawie pełny")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Zgłoś obłożenie")
            .setItems(options) { _, which ->
                val status = when (which) { 0 -> ParkingStatus.GREEN; 1 -> ParkingStatus.YELLOW; else -> ParkingStatus.RED }
                FirebaseInstallations.getInstance().id
                    .addOnSuccessListener { userKey ->
                        val vote = hashMapOf("status" to status.name, "timestamp" to Timestamp.now())
                        db.collection("parking_votes").document(placeId).collection("votes")
                            .document(userKey).set(vote)
                            .addOnSuccessListener { onReported() }
                    }
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
