package com.palmclaw.attachments

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class AttachmentTooLargeException(
    maxBytes: Long
) : IOException("Attachment exceeds maximum download size (${maxBytes.coerceAtLeast(0L)} bytes)")

object BoundedStreamCopy {
    fun copy(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long
    ): Long {
        require(maxBytes >= 0L) { "maxBytes must be non-negative" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return copied
            if (copied + read > maxBytes) {
                val allowed = (maxBytes - copied).coerceAtLeast(0L).toInt()
                if (allowed > 0) {
                    output.write(buffer, 0, allowed)
                    copied += allowed
                }
                throw AttachmentTooLargeException(maxBytes)
            }
            output.write(buffer, 0, read)
            copied += read
        }
    }
}
