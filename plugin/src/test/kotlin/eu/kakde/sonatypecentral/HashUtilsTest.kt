package eu.kakde.sonatypecentral

import eu.kakde.sonatypecentral.utils.HashUtils
import eu.kakde.sonatypecentral.utils.MessageDigestAlgorithm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

class HashUtilsTest {
    @Test
    fun `calculate MD5 checksum for a file`() {
        val file = File(javaClass.classLoader.getResource("test_md5.txt")!!.toURI())
        val checkSumMd5 =
            HashUtils.getCheckSumFromFile(
                MessageDigest.getInstance(MessageDigestAlgorithm.MD5),
                file,
            )
        val checksum = "5eb63bbbe01eeed093cb22bb8f5acdc3"
        assertEquals(checksum, checkSumMd5)
    }

    @Test
    fun `calculate SHA-1 checksum for a file`() {
        val file = File(javaClass.classLoader.getResource("test_sha1.txt")!!.toURI())
        val checkSumSha1 =
            HashUtils.getCheckSumFromFile(
                MessageDigest.getInstance(MessageDigestAlgorithm.SHA_1),
                file,
            )
        val checksum = "2044dd673cbcff105e201247487b424eb9595060"
        assertEquals(checksum, checkSumSha1)
    }

    @Test
    fun `calculate checksum for an empty file`() {
        // Test case for an empty file
        val file = File(javaClass.classLoader.getResource("empty_file.txt")!!.toURI())
        val checkSum =
            HashUtils.getCheckSumFromFile(
                MessageDigest.getInstance(MessageDigestAlgorithm.MD5),
                file,
            )
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", checkSum)
    }

}
