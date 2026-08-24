package com.js8call.example.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.ChipGroup
import com.js8call.example.R
import com.js8call.example.data.LinkRepository
import com.js8call.example.util.NetworkGraph
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The who-hears-whom graph, aggregated from link observations. Tapping a
 * station opens its contact card. The chips bound how old an observation
 * may be, because on HF a link from last night is not a link right now.
 */
class NetworkMapFragment : Fragment() {

    private lateinit var mapView: NetworkMapView
    private lateinit var emptyState: TextView

    private var windowMs = 24 * 60 * 60 * 1000L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_network_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialToolbar>(R.id.map_toolbar)
            .setNavigationOnClickListener { findNavController().navigateUp() }

        mapView = view.findViewById(R.id.network_map)
        emptyState = view.findViewById(R.id.map_empty_state)

        mapView.onNodeClick = { callsign ->
            if (!callsign.equals(myCallsign(), ignoreCase = true)) {
                findNavController().navigate(
                    R.id.navigation_contact_detail,
                    Bundle().apply { putString("callsign", callsign) }
                )
            }
        }

        view.findViewById<ChipGroup>(R.id.age_chips).setOnCheckedStateChangeListener { _, ids ->
            windowMs = when (ids.firstOrNull()) {
                R.id.chip_1h -> 60 * 60 * 1000L
                R.id.chip_7d -> 7 * 24 * 60 * 60 * 1000L
                else -> 24 * 60 * 60 * 1000L
            }
            reload()
        }

        // New observations land while the map is open; the row count is a
        // cheap change signal that avoids re-querying on every decode.
        LinkRepository.getInstance(requireContext()).countLive()
            .observe(viewLifecycleOwner) { reload() }
    }

    private fun myCallsign(): String =
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getString("callsign", "")?.trim()?.uppercase(Locale.US).orEmpty()

    private fun reload() {
        val since = System.currentTimeMillis() - windowMs
        viewLifecycleOwner.lifecycleScope.launch {
            val observations = LinkRepository.getInstance(requireContext()).getSince(since)
            val graph = NetworkGraph.build(observations, myCallsign())
            emptyState.visibility = if (graph.edges.isEmpty()) View.VISIBLE else View.GONE
            mapView.setGraph(graph, myCallsign())
        }
    }
}
