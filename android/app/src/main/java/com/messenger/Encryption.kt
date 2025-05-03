package com.messenger

import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider
import org.bouncycastle.pqc.jcajce.spec.KyberParameterSpec
import java.util.Base64

/*
object Encryption {

    init {
        if (Security.getProvider("BCPQC") == null) {
            Security.addProvider(BouncyCastlePQCProvider())
        }
    }

    // Generate a Kyber512 key pair
    fun generateKyberKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("Kyber", "BCPQC")
        keyGen.initialize(KyberParameterSpec.kyber512)
        return keyGen.generateKeyPair()
    }

    // Encrypt a message using Kyber public key + AES-GCM
    fun encryptMessage(
        message: ByteArray,
        recipientPublicKeyBytes: ByteArray
    ): Pair<ByteArray, ByteArray> {
        val keyFactory = KeyFactory.getInstance("Kyber", "BCPQC")
        val pubKeySpec = X509EncodedKeySpec(recipientPublicKeyBytes)
        val recipientPublicKey = keyFactory.generatePublic(pubKeySpec)

        val kemCipher = Cipher.getInstance("Kyber", "BCPQC")
        kemCipher.init(Cipher.ENCRYPT_MODE, recipientPublicKey)

        // Kyber encapsulation: produces ciphertext + shared secret
        val cipherText = kemCipher.doFinal(null)
        val secretKeyBytes = kemCipher.getKey()!!.encoded

        // Use shared secret as AES key
        val secretKey = SecretKeySpec(secretKeyBytes, 0, 16, "AES")

        // Encrypt the actual message with AES-GCM
        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
        aesCipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))

        val encryptedMessage = aesCipher.doFinal(message)
        val fullMessage = iv + encryptedMessage

        return Pair(cipherText, fullMessage)
    }

    // Decrypt a message using Kyber private key + AES-GCM
    fun decryptMessage(
        cipherText: ByteArray,
        encryptedMessage: ByteArray,
        recipientPrivateKeyBytes: ByteArray
    ): ByteArray {
        val keyFactory = KeyFactory.getInstance("Kyber", "BCPQC")
        val privKeySpec = PKCS8EncodedKeySpec(recipientPrivateKeyBytes)
        val privateKey = keyFactory.generatePrivate(privKeySpec)

        val kemCipher = Cipher.getInstance("Kyber", "BCPQC")
        kemCipher.init(Cipher.DECRYPT_MODE, privateKey)

        kemCipher.doFinal(cipherText)
        val secretKeyBytes = kemCipher.getKey()!!.encoded
        val secretKey = SecretKeySpec(secretKeyBytes, 0, 16, "AES")

        val iv = encryptedMessage.copyOfRange(0, 12)
        val cipherPayload = encryptedMessage.copyOfRange(12, encryptedMessage.size)

        val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")
        aesCipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))

        return aesCipher.doFinal(cipherPayload)
    }

    // Utility (Optional): Encode to Base64 string
    fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    // Utility (Optional): Decode from Base64 string
    fun fromBase64(str: String): ByteArray = Base64.getDecoder().decode(str)
}
*/