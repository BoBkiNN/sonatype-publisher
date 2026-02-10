package xyz.bobkinn.sonatypepublisher.utils

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

object HashComputation {

    fun getExtension(algorithm: String) = algorithm.replace("-", "").lowercase()


    fun writeChecksum(file: File, digest: MessageDigest): File {
        val hash = HashUtils.getCheckSumFromFile(digest, file)
        val ext= getExtension(digest.algorithm)
        val fileName = "${file.name}.$ext"
        val targetFile = File(file.parent, fileName)
        writeContentToFile(targetFile, hash)
        return targetFile
    }

    fun computeAndSaveDirectoryHashes(
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

    private fun writeContentToFile(
        file: File,
        content: String,
    ) {
        file.bufferedWriter(UTF_8).use { writer ->
            writer.write(content)
        }
    }
}
