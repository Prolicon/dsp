package com.messenger

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.messenger.databinding.FragmentChatBinding
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

class ChatFragment : Fragment() {
    private lateinit var binding: FragmentChatBinding
    private lateinit var currentSession: Session
    private lateinit var messageAdapter: MessageAdapter
    private val messages = mutableListOf<Message>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sharedPrefs = requireContext().getSharedPreferences("main", Context.MODE_PRIVATE)
        val sessionId = sharedPrefs.getLong("currentSessionId", -1)

        binding.chatUserName.text = arguments?.getString("userName") ?: "name"
        val userId = arguments?.getString("userId") ?: ""

        // Initialize adapter with empty list
        messageAdapter = MessageAdapter(messages)
        binding.messagesRecyclerView.apply {
            adapter = messageAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        binding.back.setOnClickListener {
            findNavController().navigate(R.id.contactsFragment)
        }

        // Set up send button click listener
        binding.sendButton.setOnClickListener {
            val messageText = binding.messageInput.text.toString().trim()
            if (messageText.isNotEmpty()) {
                sendMessage(userId, messageText)
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
                .getAllMessagesInChannel(userId, currentSession.id)

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

    private fun sendMessage(recipientId: String, messageText: String) {
        val serverAddress = currentSession.serverAddress
        val retrofit = Retrofit.Builder()
            .baseUrl("http://$serverAddress")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(MessengerApiServiceChat::class.java)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Send message to server
                val response = service.sendMessage(
                    recipient = recipientId,
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
                        isGroupChannel = false,
                        timestamp = System.currentTimeMillis().toString(),
                        messageContent = messageText
                    )

                    DatabaseProvider.getDatabase(requireContext())
                        .messageDao()
                        .insertMessage(message)

                    // Update UI
                    withContext(Dispatchers.Main) {
                        messages.add(message)
                        messageAdapter.notifyItemInserted(messages.size - 1)
                    }
                } else {
                    // Handle error
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

class MessageAdapter(private val messages: List<Message>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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
                // Change background tint for right-aligned messages
                //val color = Color.parseColor("#FF5733") // Your custom hex color
                //rightHolder.binding.messageTextView.backgroundTintList = ColorStateList.valueOf(color)
            }
            2 -> {
                val leftHolder = holder as MessageLeftViewHolder
                leftHolder.binding.messageTextView.text = message.messageContent
                // Change background tint for left-aligned messages
                //val color = Color.parseColor("#00FF00") // Another custom hex color
                //leftHolder.binding.messageTextView.backgroundTintList = ColorStateList.valueOf(color)
            }
        }
    }

    override fun getItemCount(): Int = messages.size
}


// Add this to a new file or at the top of ChatFragment.kt
private interface MessengerApiServiceChat {
    @FormUrlEncoded
    @POST("send/user/{recipient}/")
    suspend fun sendMessage(
        @Path("recipient") recipient: String,
        @Field("name") name: String,
        @Field("token") token: String,
        @Field("message") message: String
    ): Response<SendMessageResponse>
}

data class SendMessageResponse(
    val status: Int,
    val detail: String,
    val message_id: Int?
)