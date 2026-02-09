package eu.kakde.sonatypecentral.utils

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

object HashUtils {
    private const val STREAM_BUFFER_LENGTH = 1024
    
    fun getCheckSumFromFile(digest: MessageDigest, file: File, toLowerCase: Boolean = true): String {
        FileInputStream(file).use { fis ->
            val hashBytes = updateDigest(digest, fis).digest()
            return hashBytes.toHex(toLowerCase)
        }
    }

    private fun updateDigest(digest: MessageDigest, data: InputStream): MessageDigest {
        val buffer = ByteArray(STREAM_BUFFER_LENGTH)
        var read = data.read(buffer)
        while (read > 0) {
            digest.update(buffer, 0, read)
            read = data.read(buffer)
        }
        return digest
    }

    /** Convert bytes to hex string, lowercase or uppercase */
    private fun ByteArray.toHex(toLowerCase: Boolean): String {
        return joinToString("") { if (toLowerCase) "%02x".format(it) else "%02X".format(it) }
    }
}
