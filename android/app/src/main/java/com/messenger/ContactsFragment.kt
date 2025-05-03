package com.messenger

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.messenger.databinding.FragmentContactsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

class ContactsFragment : Fragment() {
    private lateinit var binding: FragmentContactsBinding
    private lateinit var currentSession: Session

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val sharedPrefs = requireContext().getSharedPreferences("main", Context.MODE_PRIVATE)
        val sessionId = sharedPrefs.getLong("currentSessionId", -1)

        println("SESSIONID IS : $sessionId")

        lifecycleScope.launch(Dispatchers.IO) {
            val sessionDao = DatabaseProvider.getDatabase(requireContext()).sessionDao()
            currentSession = sessionDao.getSessionById(sessionId) ?: Session(
                id = -1,
                serverAddress = "null",
                userId = "null",
                userName = "null",
                userToken = "null",
                privateKey = "null"
            )

            withContext(Dispatchers.Main) {
                binding.serverName.text = currentSession.serverAddress
                binding.userName.text = currentSession.userId
            }

            loadContacts()
            setupMessageFetching()
        }


        binding.sessionsButton.setOnClickListener {
            findNavController().navigate(R.id.sessionsFragment)
        }

        // Initialize FAB click listener
        binding.addButton.setOnClickListener {
            showUserSearchDialog()
        }

        binding.groupButton.setOnClickListener {
            showCreateGroupDialog()
        }

        //loadContacts()
    }

    private suspend fun loadContacts() {
        //lifecycleScope.launch(Dispatchers.IO) {
            val contacts = DatabaseProvider.getDatabase(requireContext()).contactDao().getAllContacts(currentSession.id)
            withContext(Dispatchers.Main) {
                binding.contactsRecyclerView.adapter = ContactsAdapter(contacts) { contact ->
                    if (contact.isGroupChannel) {
                        findNavController().navigate(
                            R.id.openGroupChat,
                            bundleOf("groupId" to contact.id, "groupName" to contact.name)
                        )
                    } else {
                        findNavController().navigate(
                            R.id.openChat,
                            bundleOf("userId" to contact.id, "userName" to contact.name)
                        )
                    }
                }
            }
        //}
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
                searchUserOnServer(userIdToSearch)
                dialog.dismiss()
            } else {
                userIdInput.error = "Please enter a user ID"
            }
        }

        dialog.show()
    }

    private fun searchUserOnServer(userIdToSearch: String) {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    searchUserOnServer(
                        currentSession.serverAddress,
                        currentSession.userId,
                        currentSession.userToken,
                        userIdToSearch
                    )
                }
                    handleSearchResponse(response, userIdToSearch)
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error searching user: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private suspend fun searchUserOnServer(
        serverAddress: String,
        requesterId: String,
        token: String,
        userIdToSearch: String
    ): Response<UserResponse> {
        val retrofit = Retrofit.Builder()
            .baseUrl("http://$serverAddress")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(MessengerApiServiceContact::class.java)

        return service.getUserDetails(
            userId = userIdToSearch,
            requesterId = requesterId,
            token = token
        )
    }

    private fun handleSearchResponse(response: Response<UserResponse>, userIdSearched: String) {
        if (response.isSuccessful) {
            val user = response.body()
            user?.let {
                // Add the user to contacts if not already present
                lifecycleScope.launch(Dispatchers.IO) {
                    val contactDao = DatabaseProvider.getDatabase(requireContext()).contactDao()
                    val currentTime = System.currentTimeMillis() // Current timestamp as priority

                    if (contactDao.getContactById(it.user_id, currentSession.id) == null) {
                        contactDao.insertContact(
                            Contact(
                                id = it.user_id,
                                name = it.name,
                                publicKey = it.public_key,
                                session = currentSession.id,  // Using the current session ID
                                isGroupChannel = false,
                                priority = currentTime  // Using current timestamp
                            )
                        )
                    } else {
                        // Update existing contact if needed
                        contactDao.updateContact(
                            Contact(
                                id = it.user_id,
                                name = it.name,
                                publicKey = it.public_key,
                                session = currentSession.id,
                                isGroupChannel = false,
                                priority = currentTime
                            )
                        )
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            "Contact ${it.name} added/updated",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadContacts() // Refresh the contacts list
                    }
                }
            } ?: run {
                Toast.makeText(
                    requireContext(),
                    "User $userIdSearched not found",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            Toast.makeText(
                requireContext(),
                "Error: ${response.code()} - ${response.message()}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showCreateGroupDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_creategroup, null)
        val groupIdInput = dialogView.findViewById<TextInputEditText>(R.id.groupIdInput)
        val groupNameInput = dialogView.findViewById<TextInputEditText>(R.id.groupNameInput)
        val createButton = dialogView.findViewById<Button>(R.id.createButton)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        createButton.setOnClickListener {
            val groupId = groupIdInput.text.toString().trim()
            val groupName = groupNameInput.text.toString().trim()

            if (groupId.isEmpty()) {
                groupIdInput.error = "Please enter a group ID"
                return@setOnClickListener
            }

            if (groupName.isEmpty()) {
                groupNameInput.error = "Please enter a group name"
                return@setOnClickListener
            }

            createGroupOnServer(groupId, groupName)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun createGroupOnServer(groupId: String, groupName: String) {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    createGroupOnServer(
                        currentSession.serverAddress,
                        currentSession.userId,
                        currentSession.userToken,
                        groupId,
                        groupName
                    )
                }

                if (response.isSuccessful) {
                    val groupResponse = response.body()
                    groupResponse?.let {
                        // Add the group to contacts
                        lifecycleScope.launch(Dispatchers.IO) {
                            val contactDao = DatabaseProvider.getDatabase(requireContext()).contactDao()
                            val currentTime = System.currentTimeMillis()

                            contactDao.insertContact(
                                Contact(
                                    id = groupId,
                                    name = groupName,
                                    publicKey = "", // Groups don't need public keys
                                    session = currentSession.id,
                                    isGroupChannel = true,
                                    priority = currentTime
                                )
                            )

                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    requireContext(),
                                    "Group $groupName created successfully",
                                    Toast.LENGTH_SHORT
                                ).show()
                                loadContacts() // Refresh the contacts list
                            }
                        }
                    }
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Error creating group: ${response.code()} - ${response.message()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Error creating group: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private suspend fun createGroupOnServer(
        serverAddress: String,
        creatorId: String,
        token: String,
        groupId: String,
        groupName: String
    ): Response<GroupResponse> {
        val retrofit = Retrofit.Builder()
            .baseUrl("http://$serverAddress")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(MessengerApiServiceContact::class.java)

        return service.createGroup(
            creatorId = creatorId,
            token = token,
            groupName = groupName,
            groupId = groupId
        )
    }

    // Add this function to fetch and process messages
    private suspend fun fetchAndProcessMessages() {
        try {
            val response = withContext(Dispatchers.IO) {
                getMessagesFromServer(
                    currentSession.serverAddress,
                    currentSession.userId,
                    currentSession.userToken
                )
            }

            if (response.isSuccessful) {
                val messageResponse = response.body()
                if (messageResponse?.status == 1 && !messageResponse.messages.isNullOrEmpty()) {
                    // Process received messages
                    processReceivedMessages(messageResponse.messages)

                    // Acknowledge messages after processing
                    val ackResponse = withContext(Dispatchers.IO) {
                        acknowledgeMessagesOnServer(
                            currentSession.serverAddress,
                            currentSession.userId,
                            currentSession.userToken
                        )
                    }

                    if (!ackResponse.isSuccessful || ackResponse.body()?.status != 1) {
                        Log.e("ContactsFragment", "Failed to acknowledge messages")
                    }

                }
            } else {
                Log.e("ContactsFragment", "Failed to fetch messages: ${response.code()}")
            }
        } catch (e: Exception) {
            Log.e("ContactsFragment", "Error fetching messages", e)
        }
    }

    private suspend fun getMessagesFromServer(
        serverAddress: String,
        userId: String,
        token: String
    ): Response<MessageResponse> {
        val retrofit = Retrofit.Builder()
            .baseUrl("http://$serverAddress")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(MessengerApiServiceContact::class.java)
        return service.getMessages(userId, token)
    }

    private suspend fun acknowledgeMessagesOnServer(
        serverAddress: String,
        userId: String,
        token: String
    ): Response<AcknowledgeResponse> {
        val retrofit = Retrofit.Builder()
            .baseUrl("http://$serverAddress")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val service = retrofit.create(MessengerApiServiceContact::class.java)
        return service.acknowledgeMessages(userId, token)
    }

    private suspend fun processReceivedMessages(messages: List<MessageItem>) {
        val messageDao = DatabaseProvider.getDatabase(requireContext()).messageDao()
        val contactDao = DatabaseProvider.getDatabase(requireContext()).contactDao()

        messages.forEach { message ->
            // Check if a contact exists for the sender
            val existingContact = contactDao.getContactById(message.channel, currentSession.id)

            if (existingContact == null) {
                if (message.is_group) {
                    // For group messages, create a contact with blank public key
                    contactDao.insertContact(
                        Contact(
                            id = message.channel,
                            name = message.channel,
                            publicKey = "",
                            session = currentSession.id,
                            isGroupChannel = true,
                            priority = System.currentTimeMillis()
                        )
                    )
                } else {
                    // For individual messages, try to fetch user details from the server
                    try {
                        val response = withContext(Dispatchers.IO) {
                            searchUserOnServer(
                                currentSession.serverAddress,
                                currentSession.userId,
                                currentSession.userToken,
                                message.channel
                            )
                        }

                        if (response.isSuccessful) {
                            val user = response.body()
                            user?.let {
                                // Create a new contact entry
                                contactDao.insertContact(
                                    Contact(
                                        id = it.user_id,
                                        name = it.name,
                                        publicKey = it.public_key,
                                        session = currentSession.id,
                                        isGroupChannel = false,
                                        priority = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ContactsFragment", "Failed to fetch user details for ${message.sender}", e)
                    }
                }
            }

            // Insert the message
            messageDao.insertMessage(
                Message(
                    senderId = message.sender,
                    session = currentSession.id,
                    channelId = message.channel,
                    isGroupChannel = message.is_group,
                    timestamp = message.timestamp,
                    messageContent = message.content
                )
            )
            loadContacts() // Refresh the contacts list
        }
    }

    // Call this in onViewCreated after loading contacts
    private fun setupMessageFetching() {
        lifecycleScope.launch {
            fetchAndProcessMessages()
        }
    }
}

// Adapter for RecyclerView
class ContactsAdapter(
    private val contacts: List<Contact>,
    private val onItemClick: (Contact) -> Unit
) : RecyclerView.Adapter<ContactsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.contactName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = contacts[position]
        holder.name.text = contact.name
        holder.itemView.setOnClickListener { onItemClick(contact) }
    }

    override fun getItemCount() = contacts.size
}

// Retrofit interface
interface MessengerApiServiceContact {
    @GET("user/{user_id}/")
    suspend fun getUserDetails(
        @Path("user_id") userId: String,
        @Query("requester_id") requesterId: String,
        @Query("token") token: String
    ): Response<UserResponse>

    @POST("group/create")
    @FormUrlEncoded
    suspend fun createGroup(
        @Field("creator_id") creatorId: String,
        @Field("token") token: String,
        @Field("group_name") groupName: String,
        @Field("group_id") groupId: String
    ): Response<GroupResponse>

    @POST("get/")
    @FormUrlEncoded
    suspend fun getMessages(
        @Field("name") name: String,
        @Field("token") token: String
    ): Response<MessageResponse>

    @POST("acknowledge/")
    @FormUrlEncoded
    suspend fun acknowledgeMessages(
        @Field("name") name: String,
        @Field("token") token: String
    ): Response<AcknowledgeResponse>
}

data class MessageResponse(
    val status: Int,
    val messages: List<MessageItem>?,
    val detail: String?
)

data class MessageItem(
    val sender: String,
    val channel: String,
    val content: String,
    val timestamp: String,
    val is_group: Boolean
)

data class AcknowledgeResponse(
    val status: Int,
    val detail: String,
    val deleted_count: Int,
    val previous_message_count: Int
)

data class GroupResponse(
    val status: Int,
    val group_id: String,
    val group_name: String,
    val members: List<String>
)

// Response data class
data class UserResponse(
    val status: Int,
    val user_id: String,
    val name: String,
    val public_key: String
)