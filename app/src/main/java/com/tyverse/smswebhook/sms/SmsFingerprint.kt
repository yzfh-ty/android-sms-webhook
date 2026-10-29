package com.tyverse.smswebhook.sms

import java.nio.ByteBuffer
import java.security.MessageDigest

object SmsFingerprint {
    fun create(deviceId: String, subscription: Int?, pdus: List<ByteArray>): String {
        require(pdus.isNotEmpty())
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(bytes: ByteArray) {
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        add(deviceId.toByteArray(Charsets.UTF_8))
        add(ByteBuffer.allocate(4).putInt(subscription ?: -1).array())
        pdus.forEach(::add)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
