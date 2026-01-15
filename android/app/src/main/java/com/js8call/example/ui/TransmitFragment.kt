package com.js8call.example.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Filter
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.js8call.example.R
import com.js8call.example.model.TransmitState
import com.js8call.example.service.JS8EngineService

/**
 * Fragment for composing and transmitting messages.
 */
class TransmitFragment : Fragment() {

    private lateinit var viewModel: TransmitViewModel

    private lateinit var messageEditText: TextInputEditText
    private lateinit var directedEditText: TextInputEditText
    private lateinit var sendButton: Button
    private lateinit var queueRecyclerView: RecyclerView
    private lateinit var queueEmptyText: TextView
    private lateinit var txStatusText: TextView
    private lateinit var modeSelect: AutoCompleteTextView
    private lateinit var speedSelect: AutoCompleteTextView
    private var selectedMode: TxMode = TxMode.FREE_TEXT
    private var selectedSubmode: Int = SUBMODE_NORMAL
    private var defaultSendButtonTint: ColorStateList? = null
    private var modeOptions: List<ModeOption> = emptyList()
    private lateinit var queueAdapter: TransmitQueueAdapter
    private var currentTxOffset: Float = 1500f

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PREF_AUTOREPLY_ENABLED) {
                updateModeOptions()
            }
        }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                JS8EngineService.ACTION_TX_STATE -> {
                    val state = intent.getStringExtra(JS8EngineService.EXTRA_TX_STATE)
                    handleTxState(state)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_transmit, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize ViewModel
        viewModel = ViewModelProvider(requireActivity())[TransmitViewModel::class.java]

        // Find views
        messageEditText = view.findViewById(R.id.message_edit_text)
        directedEditText = view.findViewById(R.id.directed_edit_text)
        sendButton = view.findViewById(R.id.send_button)
        queueRecyclerView = view.findViewById(R.id.queue_recycler_view)
        queueEmptyText = view.findViewById(R.id.queue_empty_text)
        txStatusText = view.findViewById(R.id.tx_status_text)
        modeSelect = view.findViewById(R.id.tx_mode_select)
        speedSelect = view.findViewById(R.id.tx_speed_select)
        defaultSendButtonTint = sendButton.backgroundTintList

        queueAdapter = TransmitQueueAdapter()
        queueRecyclerView.adapter = queueAdapter

        // Set up listeners
        setupListeners()
        setupModeSelector()
        setupSpeedSelector()

        // Observe ViewModel
        observeViewModel()

        // Register broadcast receiver
        registerBroadcastReceiver()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        unregisterBroadcastReceiver()
    }

    override fun onStart() {
        super.onStart()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(preferenceListener)
        updateModeOptions()
    }

    override fun onStop() {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(preferenceListener)
        super.onStop()
    }

    private fun setupListeners() {
        // Message text changes
        messageEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setComposedMessage(s?.toString() ?: "")
                updateSendButtonState()
            }
        })

        // Directed callsign changes
        directedEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setDirectedTo(s?.toString() ?: "")
            }
        })

        // Send button
        sendButton.setOnClickListener {
            sendMessage()
        }
    }

    private fun setupModeSelector() {
        modeSelect.setOnItemClickListener { _, _, position, _ ->
            selectedMode = modeOptions.getOrNull(position)?.mode ?: TxMode.FREE_TEXT
            updateSendButtonState()
        }
    }

    private fun updateModeOptions() {
        modeOptions = listOf(
            ModeOption(getString(R.string.tx_mode_free_text), TxMode.FREE_TEXT),
            ModeOption(getString(R.string.tx_mode_cq), TxMode.CQ),
            ModeOption(heartbeatLabel(), TxMode.HEARTBEAT)
        )

        val labels = modeOptions.map { it.label }
        modeSelect.setAdapter(
            NoFilterArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                labels
            )
        )

        val selectedIndex = modeOptions.indexOfFirst { it.mode == selectedMode }
            .takeIf { it >= 0 } ?: 0
        modeSelect.setText(modeOptions[selectedIndex].label, false)
        selectedMode = modeOptions[selectedIndex].mode
        updateSendButtonState()
    }

    private fun heartbeatLabel(): String {
        return if (isAutoreplyEnabled()) {
            getString(R.string.tx_mode_hb_ack)
        } else {
            getString(R.string.tx_mode_heartbeat)
        }
    }

    private fun isAutoreplyEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return prefs.getBoolean(PREF_AUTOREPLY_ENABLED, false)
    }

    private fun setupSpeedSelector() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val speedOptions = listOf(
            SpeedOption(getString(R.string.tx_speed_slow), SUBMODE_SLOW),
            SpeedOption(getString(R.string.tx_speed_normal), SUBMODE_NORMAL),
            SpeedOption(getString(R.string.tx_speed_fast), SUBMODE_FAST),
            SpeedOption(getString(R.string.tx_speed_turbo), SUBMODE_TURBO)
        )

        val adapter = NoFilterArrayAdapter(
            requireContext(),
            android.R.layout.simple_list_item_1,
            speedOptions.map { it.label }
        )
        speedSelect.setAdapter(adapter)

        val savedSubmode = prefs.getInt(PREF_TX_SUBMODE, SUBMODE_NORMAL)
        val defaultIndex = speedOptions.indexOfFirst { it.submode == savedSubmode }
            .takeIf { it >= 0 } ?: 0
        speedSelect.setText(speedOptions[defaultIndex].label, false)
        selectedSubmode = speedOptions[defaultIndex].submode

        speedSelect.setOnItemClickListener { _, _, position, _ ->
            selectedSubmode = speedOptions.getOrNull(position)?.submode ?: SUBMODE_NORMAL
            prefs.edit().putInt(PREF_TX_SUBMODE, selectedSubmode).apply()
        }
    }

    private fun registerBroadcastReceiver() {
        val filter = IntentFilter().apply {
            addAction(JS8EngineService.ACTION_TX_STATE)
        }
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(broadcastReceiver, filter)
    }

    private fun unregisterBroadcastReceiver() {
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(broadcastReceiver)
    }

    private fun handleTxState(state: String?) {
        // TX state updates are handled centrally by MainActivity
        // This receiver is kept for UI updates that observe the ViewModel
    }

    private fun observeViewModel() {
        // Observe composed message
        viewModel.composedMessage.observe(viewLifecycleOwner) { text ->
            if (text.isEmpty() && messageEditText.text?.toString()?.isNotEmpty() == true) {
                messageEditText.text?.clear()
            }
        }

        viewModel.directedTo.observe(viewLifecycleOwner) { callsign ->
            val current = directedEditText.text?.toString().orEmpty()
            if (callsign.isNotBlank() && callsign != current) {
                directedEditText.setText(callsign)
            }
        }

        viewModel.txOffsetHz.observe(viewLifecycleOwner) { offset ->
            currentTxOffset = offset
            updateTxStatusText(viewModel.txState.value)
        }

        // Observe TX state
        viewModel.txState.observe(viewLifecycleOwner) { state ->
            val safeState = state ?: TransmitState.IDLE
            updateTxStatusText(safeState)
            updateSendButtonTint(safeState)
        }

        // Observe queue
        viewModel.queue.observe(viewLifecycleOwner) { queue ->
            queueAdapter.submitList(queue)
            if (queue.isEmpty()) {
                queueRecyclerView.visibility = View.GONE
                queueEmptyText.visibility = View.VISIBLE
            } else {
                queueRecyclerView.visibility = View.VISIBLE
                queueEmptyText.visibility = View.GONE
            }
        }
    }

    private fun updateSendButtonState() {
        val hasText = messageEditText.text?.isNotBlank() == true
        sendButton.isEnabled = selectedMode != TxMode.FREE_TEXT || hasText
    }

    private fun updateTxStatusText(state: TransmitState?) {
        val safeState = state ?: TransmitState.IDLE
        txStatusText.text = when (safeState) {
            TransmitState.IDLE -> "Ready to transmit at ${currentTxOffset.toInt()} Hz"
            TransmitState.QUEUED -> getString(R.string.tx_status_queued)
            TransmitState.TRANSMITTING -> getString(R.string.tx_status_transmitting)
        }
    }

    private fun updateSendButtonTint(state: TransmitState) {
        when (state) {
            TransmitState.IDLE -> sendButton.backgroundTintList = defaultSendButtonTint
            TransmitState.QUEUED -> {
                val color = ContextCompat.getColor(requireContext(), R.color.tx_button_queued)
                sendButton.backgroundTintList = ColorStateList.valueOf(color)
            }
            TransmitState.TRANSMITTING -> {
                val color = ContextCompat.getColor(requireContext(), R.color.tx_button_transmitting)
                sendButton.backgroundTintList = ColorStateList.valueOf(color)
            }
        }
    }

    private fun sendMessage() {
        if (!hasCallsignConfigured()) {
            Snackbar.make(
                requireView(),
                R.string.error_callsign_required,
                Snackbar.LENGTH_LONG
            ).show()
            return
        }
        val text = messageEditText.text?.toString()?.trim().orEmpty()
        val (payloadText, directed) = when (selectedMode) {
            TxMode.FREE_TEXT -> {
                if (text.isEmpty()) {
                    Snackbar.make(requireView(), "Enter a message", Snackbar.LENGTH_SHORT).show()
                    return
                }
                val target = directedEditText.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                text to target
            }
            TxMode.CQ -> "CQ CQ CQ" to null
            TxMode.HEARTBEAT -> "HB" to null
        }

        // Queue the message - MainActivity will handle sending to service
        viewModel.queueMessage(payloadText, directed)

        // Broadcast to trigger queue processing (MainActivity handles this)
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(
            Intent(com.js8call.example.MainActivity.ACTION_PROCESS_TX_QUEUE)
        )

        // Show confirmation
        val message = if (directed != null) {
            "Message queued for $directed"
        } else {
            "Message queued"
        }
        Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
    }

    private fun hasCallsignConfigured(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val callsign = prefs.getString("callsign", "")?.trim().orEmpty()
        return callsign.isNotBlank()
    }

    private data class SpeedOption(val label: String, val submode: Int)
    private data class ModeOption(val label: String, val mode: TxMode)

    private enum class TxMode {
        FREE_TEXT,
        CQ,
        HEARTBEAT
    }

    private class NoFilterArrayAdapter(
        context: android.content.Context,
        layoutResId: Int,
        private val items: List<String>
    ) : ArrayAdapter<String>(context, layoutResId, items) {
        private val noFilter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                return FilterResults().apply {
                    values = items
                    count = items.size
                }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = noFilter
    }

    companion object {
        private const val SUBMODE_NORMAL = 0
        private const val SUBMODE_FAST = 1
        private const val SUBMODE_TURBO = 2
        private const val SUBMODE_SLOW = 4
        private const val PREF_AUTOREPLY_ENABLED = "autoreply_enabled"
        private const val PREF_TX_SUBMODE = "tx_submode"
    }
}
