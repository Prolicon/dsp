package com.messenger

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.pqc.jcajce.spec.KyberParameterSpec
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.io.BufferedWriter
import java.io.OutputStream
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.util.Base64

class SessionSetupFragment : Fragment() {

    private lateinit var serverAddressEditText: EditText
    private lateinit var verifyButton: Button
    private lateinit var usernameEditText: EditText
    private lateinit var displayNameEditText: EditText
    private lateinit var credentialsLayout: LinearLayout
    private lateinit var createAccountButton: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_session_setup, container, false)

        serverAddressEditText = view.findViewById(R.id.serverAddressEditText)
        verifyButton = view.findViewById(R.id.verifyButton)
        usernameEditText = view.findViewById(R.id.usernameEditText)
        displayNameEditText = view.findViewById(R.id.displayNameEditText)
        credentialsLayout = view.findViewById(R.id.credentialsLayout)
        createAccountButton = view.findViewById(R.id.createAccountButton)

        credentialsLayout.visibility = View.GONE // Initially hide username/display name fields

        verifyButton.setOnClickListener {
            val serverAddress = serverAddressEditText.text.toString()
            verifyServer(serverAddress)
        }

        createAccountButton.setOnClickListener {
            val serverAddress = serverAddressEditText.text.toString()
            val username = usernameEditText.text.toString()
            val displayName = displayNameEditText.text.toString()
            registerUser(serverAddress, username, displayName)
        }

        return view
    }

    private fun verifyServer(serverAddress: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("http://$serverAddress/ping/")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000 // 5 seconds timeout
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }.trim()
                    if (response == "Hello Messenger") {
                        withContext(Dispatchers.Main) {
                            credentialsLayout.visibility = View.VISIBLE
                            Toast.makeText(
                                requireContext(),
                                "Server Verified!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                "Invalid Response: $response",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            "Server Verification Failed: HTTP $responseCode",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Server Verification Failed: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun registerUser(serverAddress: String, username: String, displayName: String) {
        val keyGen = KeyPairGenerator.getInstance("Kyber", "BCPQC")
        keyGen.initialize(KyberParameterSpec.kyber512)
        val keyPair = keyGen.generateKeyPair()

        val publicKeyBytes = keyPair.public.encoded
        val privateKeyBytes = keyPair.private.encoded

        val publicKeyEncoded = URLEncoder.encode(Base64.getEncoder().encodeToString(publicKeyBytes))
        val privateKey = Base64.getEncoder().encodeToString(privateKeyBytes)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL("http://$serverAddress/register/")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true // Enable output for POST request
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                // Prepare the request body
                val postData = "user_id=$username&name=$displayName&public_key=$publicKeyEncoded"


                withContext(Dispatchers.Main) { println("Request body: $postData") }

                val outputStream: OutputStream = connection.outputStream
                val writer = BufferedWriter(OutputStreamWriter(outputStream, "UTF-8"))
                writer.write(postData)
                writer.flush()
                writer.close()
                outputStream.close()

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }.trim()
                    val jsonResponse = JSONObject(response)
                    val status = jsonResponse.getInt("status")

                    if (status == 1) {
                        val token = jsonResponse.getString("token")

                        val sessionDao = DatabaseProvider.getDatabase(requireContext()).sessionDao()
                        val sessionId = sessionDao.insertSession(Session(serverAddress=serverAddress, userId=username,
                            userName=displayName, userToken=token, privateKey=privateKey))

                        println("CREATED SESSION ID SHOULD BE $sessionId")

                        with(requireActivity()
                            .getSharedPreferences("main", Context.MODE_PRIVATE)
                            .edit()) {
                            putLong("currentSessionId", sessionId)
                            apply()
                        }

                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                "User Registered Successfully!",
                                Toast.LENGTH_SHORT
                            ).show()
                            findNavController().navigate(
                                R.id.contactsFragment,
                                bundleOf("sessionId" to serverAddress, "userName" to username)
                            )
                        }
                    } else if (status == 2) {
                        val detail = jsonResponse.getString("detail")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                "Registration Failed: $detail",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                "Registration Failed: Unknown status",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            "Registration Failed: HTTP $responseCode",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Registration Failed: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
