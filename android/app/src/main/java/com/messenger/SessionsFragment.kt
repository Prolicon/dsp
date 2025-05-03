package com.messenger

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.messenger.databinding.FragmentSessionsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class SessionsFragment : Fragment() {
    private lateinit var binding: FragmentSessionsBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSessionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.addButton.setOnClickListener {
            findNavController().navigate(R.id.sessionSetupFragment)
        }

       var sessions : List<Session>
        lifecycleScope.launch(Dispatchers.IO) {
            sessions = DatabaseProvider.getDatabase(requireContext()).sessionDao().getAllSessions()

            withContext(Dispatchers.Main) { // Switch back to the main thread for UI updates
                binding.sessionsRecyclerView.adapter = SessionsAdapter(sessions) { session ->
                    with(requireActivity()
                        .getSharedPreferences("main", Context.MODE_PRIVATE)
                        .edit()) {
                        putLong("currentSessionId", session.id)
                        apply()
                    }
                    findNavController().navigate(
                        R.id.contactsFragment
                    )
                }
            }
        }
    }
}

// Adapter for RecyclerView
class SessionsAdapter(
    private val sessions: List<Session>,
    private val onItemClick: (Session) -> Unit
) : RecyclerView.Adapter<SessionsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val serverId: TextView = view.findViewById(R.id.serverId)
        val userId: TextView = view.findViewById(R.id.userId)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = sessions[position]
        holder.serverId.text = session.serverAddress
        holder.userId.text = session.userId
        holder.itemView.setOnClickListener { onItemClick(session) }
    }

    override fun getItemCount() = sessions.size
}