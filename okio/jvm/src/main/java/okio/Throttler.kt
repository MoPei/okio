/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okio

import java.io.IOException
import java.io.InterruptedIOException

/**
 * Enables limiting of Source and Sink throughput. Attach to this throttler via [.source] and
 * [.sink] and set the desired throughput via [.bytesPerSecond]. Multiple Sources and Sinks can be
 * attached to a single Throttler and they will be throttled as a group, where their combined
 * throughput will not exceed the desired throughput. The same Source or Sink can be attached to
 * multiple Throttlers and its throughput will not exceed the desired throughput of any of the
 * Throttlers.
 *
 * This class has these tuning parameters:
 *
 *  * `bytesPerSecond`: Maximum sustained throughput. Use 0 for no limit.
 *  * `waitByteCount`: When the requested byte count is greater than this many bytes and isn't
 *    immediately available, only wait until we can allocate at least this many bytes. Use this to
 *    set the ideal byte count during sustained throughput.
 *  * `maxByteCount`: Maximum number of bytes to allocate on any call. This is also the number of
 *    bytes that will be returned before any waiting.
 */
class Throttler {
  private var bytesPerSecond: Long = 0L
  private var waitByteCount: Long = 8 * 1024 // 8 KiB.
  private var maxByteCount: Long = 256 * 1024 // 256 KiB.
  private var nanosForMaxByteCount: Long = -1L

  /**
   * The nanoTime that we've consumed all bytes through. This is never greater than the current
   * nanoTime plus nanosForMaxByteCount.
   */
  private var allocatedUntil: Long = System.nanoTime()

  /** Sets the rate at which bytes will be allocated. Use 0 for no limit. */
  @JvmOverloads
  fun bytesPerSecond(
    bytesPerSecond: Long,
    waitByteCount: Long = this.waitByteCount,
    maxByteCount: Long = this.maxByteCount
  ) {
    synchronized(this) {
      require(bytesPerSecond >= 0)
      require(waitByteCount > 0)
      require(maxByteCount >= waitByteCount)

      this.bytesPerSecond = bytesPerSecond
      this.waitByteCount = waitByteCount
      this.maxByteCount = maxByteCount
      this.nanosForMaxByteCount = when {
        bytesPerSecond != 0L -> maxByteCount * 1_000_000_000L / bytesPerSecond
        else -> -1L
      }

      (this as Object).notifyAll()
    }
  }

  /**
   * Take up to `byteCount` bytes, waiting if necessary. Returns the number of bytes that were
   * taken.
   */
  internal fun take(byteCount: Long): Long {
    require(byteCount > 0)

    synchronized(this) {
      while (true) {
        if (bytesPerSecond == 0L) return byteCount // No limits.

        val now = System.nanoTime()
        val idleInNanos = maxOf(allocatedUntil - now, 0L)
        val usableNanos = nanosForMaxByteCount - idleInNanos
        val immediateBytes = bytesPerSecond * usableNanos / 1_000_000_000L

        // Fulfill the entire request without waiting.
        if (immediateBytes >= byteCount) {
          val byteCountNanos = byteCount * 1_000_000_000L / bytesPerSecond
          allocatedUntil = now + idleInNanos + byteCountNanos
          return byteCount
        }

        // Fulfill a big-enough block without waiting.
        if (immediateBytes >= waitByteCount) {
          allocatedUntil = now + idleInNanos + usableNanos
          return immediateBytes
        }

        // Wait until we can write some bytes.
        val byteCountNanos = minOf(waitByteCount, byteCount) * 1_000_000_000L / bytesPerSecond
        waitNanos(byteCountNanos - usableNanos)
      }
    }
    throw AssertionError() // Unreachable, but synchronized() doesn't know that.
  }

  private fun waitNanos(nanosToWait: Long) {
    val millisToWait = nanosToWait / 1_000_000L
    val remainderNanos = nanosToWait - (millisToWait * 1_000_000L)
    (this as Object).wait(millisToWait, remainderNanos.toInt())
  }

  /** Create a Source which honors this Throttler.  */
  fun source(source: Source): Source {
    return object : ForwardingSource(source) {
      override fun read(sink: Buffer, byteCount: Long): Long {
        try {
          val toRead = take(byteCount)
          return super.read(sink, toRead)
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
          throw InterruptedIOException("interrupted")
        }
      }
    }
  }

  /** Create a Sink which honors this Throttler.  */
  fun sink(sink: Sink): Sink {
    return object : ForwardingSink(sink) {
      @Throws(IOException::class)
      override fun write(source: Buffer, byteCount: Long) {
        try {
          var remaining = byteCount
          while (remaining > 0L) {
            val toWrite = take(remaining)
            super.write(source, toWrite)
            remaining -= toWrite
          }
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
          throw InterruptedIOException("interrupted")
        }
      }
    }
  }
}