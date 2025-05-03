package com.messenger

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.messenger.databinding.FragmentGroupChatBinding
import com.messenger.databinding.MessageBubbleLeftBinding
import com.messenger.databinding.MessageBubbleRightBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Path

class GroupChatFragment : Fragment() {
    private lateinit var binding: FragmentGroupChatBinding
    private lateinit var currentSession: Session
    private lateinit var messageAdapter: GroupMessageAdapter
    private val messages = mutableListOf<Message>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentGroupChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sharedPrefs = requireContext().getSharedPreferences("main", Context.MODE_PRIVATE)
        val sessionId = sharedPrefs.getLong("currentSessionId", -1)

        binding.chatUserName.text = arguments?.getString("userName") ?: "name"
        val groupId = arguments?.getString("groupId") ?: ""

        // Initialize adapter with empty list
        messageAdapter = GroupMessageAdapter(messages) { userIdToRemove ->
            removeUserFromGroup(arguments?.getString("groupId") ?: "", userIdToRemove)
        }
        binding.messagesRecyclerView.apply {
            adapter = messageAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        binding.back.setOnClickListener {
            findNavController().navigate(R.id.contactsFragment)
        }

        binding.addButton.setOnClickListener {
            showUserSearchDialog()
        }

        binding.sendButton.setOnClickListener {
            val messageText = binding.messageInput.text.toString().trim()
            if (messageText.isNotEmpty()) {
                sendMessage(groupId, messageText)
                binding.messageInput.text?.clear()
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // Load session
            val sessionDao = DatabaseProvider.getDatabase(requireContext()).sessionDao()
            currentSession = sessionDao.getSessionById(sessionId)!!

            // Load messages
            val loadedMessages = DatabaseProvider.getDatabase(requireContext())
                .messageDao()
                .getAllMessagesInChannel(groupId, currentSession.id)

            // Update UI
            withContext(Dispatchers.Main) {
                messages.clear()
                messages.addAll(loadedMessages)
                messageAdapter.notifyDataSetChanged()
            }
        }

        // Keyboard visibility listener
        val inputBox = binding.inputBox
        view.viewTreeObserver?.addOnGlobalLayoutListener {
            val r = Rect()
            view.getWindowVisibleDisplayFrame(r)
            val screenHeight = view.rootView?.height ?: 0
            val keyboardHeight = screenHeight - r.bottom

            if (keyboardHeight > screenHeight * 0.15) {
                inputBox.translationY = -keyboardHeight.toFloat()
            } else {
                inputBox.translationY = 0f
            }
        }
    }

    private fun showUserSearchDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_addcontact, null)
        val userIdInput = dialogView.findViewById<TextInputEditText>(R.id.userIdInput)
        val searchButton = dialogView.findViewById<Button>(R.id.searchButton)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        searchButton.setOnClickListener {
            val userIdToSearch = userIdInput.text.toString().trim()
            if (userIdToSearch.isNotEmpty()) {
                addMemberToGroup(arguments?.getString("groupId") ?: "", userIdToSearch)
                dialog.dismiss()
            } else {
                userIdInput.error = "Please enter a user ID"
            }
        }

        dialog.show()
    }

    private fun addMemberToGroup(groupId: String, userIdToAdd: String) {
        val serverAddress = currentSession.serverAddress
        val retrofit = Retrofit.Builder()
            .baseUrl("http://$serverAddress")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(MessengerApiServiceGroupChat::class.java)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = service.addGroupMember(
                    groupId = groupId,
                    requesterId = currentSession.userId,
                    token = currentSession.userToken,
                    newMember = userIdToAdd
                )

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body()?.status == 1) {
                        Toast.makeText(
                            requireContext(),
                            "User $userIdToAdd added to group successfully",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Optionally update your UI or local database here
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "Failed to add user: ${response.body()?.detail ?: "Unknown error"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun removeUserFromGroup(groupId: String, userIdToRemove: String) {
        val serverAddress = currentSession.serverAddress
        val retrofit = Retrofit.Builder()
            .baseUrl("http://$serverAddress")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(MessengerApiServiceGroupChat::class.java)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = service.removeGroupMember(
                    groupId = groupId,
                    creatorId = currentSession.userId,
                    token = currentSession.userToken,
                    memberId = userIdToRemove
                )

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && response.body()?.status == 1) {
                        Toast.makeText(
                            requireContext(),
                            "User $userIdToRemove removed from group successfully",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Optionally update your UI or local database here
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "Failed to remove user: ${response.body()?.detail ?: "Unknown error"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun sendMessage(recipientId: String, messageText: String) {
        val serverAddress = currentSession.serverAddress
        val retrofit = Retrofit.Builder()
            .baseUrl("http://$serverAddress")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(MessengerApiServiceGroupChat::class.java)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Send message to server
                val response = service.sendGroupMessage(
                    groupId = recipientId,
                    name = currentSession.userId,
                    token = currentSession.userToken,
                    message = messageText
                )

                if (response.isSuccessful && response.body()?.status == 1) {
                    // Message sent successfully, save to local database
                    val message = Message(
                        senderId = "Me", // Indicates this is our message
                        session = currentSession.id,
                        channelId = recipientId,
                        isGroupChannel = true,
                        timestamp = System.currentTimeMillis().toString(),
                        messageContent = messageText
                    )

                    DatabaseProvider.getDatabase(requireContext())
                        .messageDao()
                        .insertMessage(message)

                    // Signal to update the UI
                    withContext(Dispatchers.Main) {
                        messages.add(message)
                        messageAdapter.notifyItemInserted(messages.size - 1)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            "Failed to send message: ${response.body()?.detail ?: "Unknown error"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}

class GroupMessageAdapter(
    private val messages: List<Message>,
    private val onLongPressRemoveUser: ((String) -> Unit)?
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    class MessageLeftViewHolder(val binding: MessageBubbleLeftBinding) :
        RecyclerView.ViewHolder(binding.root)

    class MessageRightViewHolder(val binding: MessageBubbleRightBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].senderId == "Me") {
            1
        } else {
            2
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 1) {
            val binding = MessageBubbleRightBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return MessageRightViewHolder(binding)
        } else {
            val binding = MessageBubbleLeftBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return MessageLeftViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]

        when (holder.itemViewType) {
            1 -> {
                val rightHolder = holder as MessageRightViewHolder
                rightHolder.binding.messageTextView.text = message.messageContent
            }
            2 -> {
                val leftHolder = holder as MessageLeftViewHolder
                leftHolder.binding.messageTextView.text = "[${message.senderId}]\n${message.messageContent}"

                // Add long press listener for removing user
                leftHolder.binding.root.setOnLongClickListener {
                    onLongPressRemoveUser?.let { removeUser ->
                        MaterialAlertDialogBuilder(leftHolder.itemView.context)
                            .setTitle("Remove User")
                            .setMessage("Do you want to remove ${message.senderId} from the group?")
                            .setPositiveButton("Remove") { _, _ ->
                                removeUser(message.senderId)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    true
                }
            }
        }
    }

    override fun getItemCount(): Int = messages.size
}


// Add this to a new file or at the top of ChatFragment.kt
private interface MessengerApiServiceGroupChat {
    @POST("send/group/{group_id}/")
    @FormUrlEncoded
    suspend fun sendGroupMessage(
        @Path("group_id") groupId: String,
        @Field("name") name: String,
        @Field("token") token: String,
        @Field("message") message: String
    ): Response<SendGroupMessageResponse>

    @POST("group/{group_id}/add")
    @FormUrlEncoded
    suspend fun addGroupMember(
        @Path("group_id") groupId: String,
        @Field("requester_id") requesterId: String,
        @Field("token") token: String,
        @Field("new_member") newMember: String
    ): Response<AddGroupMemberResponse>

    @POST("group/{group_id}/remove/")
    @FormUrlEncoded
    suspend fun removeGroupMember(
        @Path("group_id") groupId: String,
        @Field("creator_id") creatorId: String,
        @Field("token") token: String,
        @Field("member_id") memberId: String
    ): Response<RemoveGroupMemberResponse>
}

data class AddGroupMemberResponse(
    val status: Int,
    val detail: String?,
    val added_members: List<String>?
)

data class RemoveGroupMemberResponse(
    val status: Int,
    val detail: String?,
    val removed_member: String?,
    val remaining_members: Int?
)

data class SendGroupMessageResponse(
    val status: Int,
    val detail: String,
    val recipient_count: Int?
)