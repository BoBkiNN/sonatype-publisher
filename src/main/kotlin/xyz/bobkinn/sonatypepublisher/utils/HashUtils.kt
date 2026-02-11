package xyz.bobkinn.sonatypepublisher.utils

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
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

    fun getExtension(algorithm: String) = algorithm.replace("-", "").lowercase()


    fun writeChecksum(file: File, digest: MessageDigest): File {
        val hash = getCheckSumFromFile(digest, file)
        val ext= getExtension(digest.algorithm)
        val fileName = "${file.name}.$ext"
        val targetFile = File(file.parent, fileName)
        targetFile.bufferedWriter(UTF_8).use {
            it.write(hash)
        }
        return targetFile
    }

    fun writesFilesHashes(
        directory: File,
        algorithms: List<String>,
    ): List<File> {
        val ret = directory.listFiles { _, name -> !name.endsWith(".asc") }?.flatMap { file ->
            for (alg in algorithms) {
                val ext = getExtension(alg)
                if (file.name.endsWith(".$ext")) return@flatMap emptyList()
            }
            algorithms.map { algorithm ->
                val digest = MessageDigest.getInstance(algorithm)
                writeChecksum(file, digest)
            }
        }
        return ret ?: listOf()
    }
}
