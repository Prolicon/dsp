package com.messenger

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import java.security.Security
import java.util.concurrent.TimeUnit

data class MessageeResponse(
    @SerializedName("status") val status: Int,
    @SerializedName("messages") val messages: List<MessageData>
)

data class MessageData(
    @SerializedName("sender") val sender: String,
    @SerializedName("channel") val channel: String,
    @SerializedName("content") val content: String,
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("is_group") val isGroup: Boolean
)

// Retrofit API Interface
interface MessageApiService {
    @FormUrlEncoded
    @POST("get/")
    fun getMessages(
        @Field("name") userId: String,
        @Field("token") userToken: String
    ): Call<MessageeResponse>
}

class MessagePollingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        println("AHHAAHAHAHAHAH")
        return try {
            // Get the current active session each time
            val sharedPrefs = applicationContext.getSharedPreferences("main", Context.MODE_PRIVATE)
            val sessionId = sharedPrefs.getLong("currentSessionId", -1)

            if (sessionId == -1L) {
                Log.d("MessagePollingWorker", "No active session found")
                return Result.failure()
            }

            // Fetch the current session details
            val sessionDao = DatabaseProvider.getDatabase(applicationContext).sessionDao()
            val currentSession = sessionDao.getSessionById(sessionId)

            if (currentSession == null) {
                Log.d("MessagePollingWorker", "Session not found in database")
                return Result.failure()
            }

            // Create Retrofit instance using the server address from current session
            val retrofit = Retrofit.Builder()
                .baseUrl(currentSession.serverAddress)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            // Create API service
            val messageApiService = retrofit.create(MessageApiService::class.java)

            // Execute API call
            val response = messageApiService.getMessages(
                userId = currentSession.userId,
                userToken = currentSession.userToken
            ).execute()

            // Check response
            if (!response.isSuccessful) {
                Log.e("MessagePollingWorker", "API call failed: ${response.code()}")
                return Result.retry()
            }

            val messageResponse = response.body()
            if (messageResponse == null) {
                Log.d("MessagePollingWorker", "No response body")
                return Result.success()
            }

            // Check status
            if (messageResponse.status != 1) {
                Log.e("MessagePollingWorker", "Server returned error status")
                return Result.failure()
            }

            // Process messages
            val messageDao = DatabaseProvider.getDatabase(applicationContext).messageDao()

            messageResponse.messages.forEach { messageData ->
                val message = Message(
                    senderId = messageData.sender,
                    session = sessionId,
                    channelId = messageData.channel,
                    isGroupChannel = messageData.isGroup,
                    timestamp = messageData.timestamp,
                    messageContent = messageData.content
                )

                // Insert message into local database
                messageDao.insertMessage(message)
            }

            // Optional: Send a broadcast or use another method to notify UI about new messages
            val intent = Intent("com.messenger.NEW_MESSAGES")
            applicationContext.sendBroadcast(intent)

            Log.d("MessagePollingWorker", "Successfully processed ${messageResponse.messages.size} messages")
            Result.success()

        } catch (e: Exception) {
            Log.e("MessagePollingWorker", "Error in message polling", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "message_polling_work"

        fun scheduleMessagePolling(context: Context) {
            val pollingWork = PeriodicWorkRequestBuilder<MessagePollingWorker>(
                10, TimeUnit.SECONDS,
                1, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,  // Keep existing work if already scheduled
                pollingWork
            )
        }

        fun cancelMessagePolling(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Security.addProvider(BouncyCastlePQCProvider())

        // Setup Navigation (FragmentContainerView in XML)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        lifecycleScope.launch {
            // Get the start destination using coroutine
            val startDestinationId = determineStartDestination()

            // Inflate and modify the navigation graph
            val navGraph = navController.navInflater.inflate(R.navigation.nav_graph).apply {
                setStartDestination(startDestinationId)
            }

            // Apply the modified graph
            navController.graph = navGraph

            // Setup message polling
            //setupMessagePolling()
        }

        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply the top inset as padding to the top-level view
            val rootView = window.decorView.rootView
            rootView.setPadding(
                rootView.paddingLeft,
                insets.top,
                rootView.paddingRight,
                rootView.paddingBottom
            )

            // Return the insets so they can be applied to other views
            WindowInsetsCompat.CONSUMED
        }
    }

    private suspend fun determineStartDestination(): Int = withContext(Dispatchers.IO) {
        val sessionDao = DatabaseProvider.getDatabase(this@MainActivity).sessionDao()
        val sharedPrefs = getSharedPreferences("main", Context.MODE_PRIVATE)

        val sessionsEmpty = sessionDao.getAllSessions().isEmpty()

        return@withContext if (sessionsEmpty) {
            R.id.sessionSetupFragment
        } else if (sharedPrefs.contains("currentSessionId")) {
            R.id.contactsFragment
        } else {
            R.id.sessionsFragment
        }
    }

    private suspend fun setupMessagePolling() = withContext(Dispatchers.IO) {
        val sharedPrefs = getSharedPreferences("main", Context.MODE_PRIVATE)
        val currentSessionId = sharedPrefs.getLong("currentSessionId", -1)
        println("SETUP MESSAGE POLLER")
        MessagePollingWorker.scheduleMessagePolling(this@MainActivity)
    }

    // Method to stop message polling (e.g., when logging out)
    fun stopMessagePolling() {
        MessagePollingWorker.cancelMessagePolling(this)
    }

    // Optional: Method to manually trigger message polling
    fun manualMessagePolling() {
        MessagePollingWorker.scheduleMessagePolling(this)
    }
}