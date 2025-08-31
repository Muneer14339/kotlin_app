package com.dss.emulator.bluetooth

import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class DataQueueManager private constructor() {
    companion object {
        private const val TAG = "DataQueueManager"
        private const val MAX_QUEUE_SIZE = 1000
        private const val POLL_TIMEOUT_MS = 100L

        @Volatile
        private var instance: DataQueueManager? = null

        fun getInstance(): DataQueueManager {
            return instance ?: synchronized(this) {
                instance ?: DataQueueManager().also { instance = it }
            }
        }
    }

    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()
    @Volatile
    private var executor: ExecutorService? = null

    fun addData(data: ByteArray): Boolean {
        if (!isRunning.get()) {
            start()
        }
        return queue.offer(data)
    }

    fun addListener(listener: (ByteArray) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (ByteArray) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            isPaused.set(false)
            executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "DataQueueProcessor").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                }
            }
            executor?.execute(::processQueue)
        }
    }

    private fun processQueue() {
        while (isRunning.get()) {
            if (isPaused.get()) {
                Thread.sleep(POLL_TIMEOUT_MS)
                continue
            }

            try {
                val data = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: continue
                listeners.forEach { listener ->
                    try {
                        listener(data)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in listener callback: ${e.message}", e)
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error processing queue: ${e.message}", e)
            }
        }
    }

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
    }

    fun isPaused(): Boolean = isPaused.get()

    @Synchronized
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            isPaused.set(false)
            executor?.shutdown()
            try {
                if (executor?.awaitTermination(1, TimeUnit.SECONDS) != true) {
                    executor?.shutdownNow()
                }
            } catch (e: InterruptedException) {
                executor?.shutdownNow()
                Thread.currentThread().interrupt()
            } finally {
                executor = null
            }
            queue.clear()
        }
    }

    fun isActive(): Boolean = isRunning.get()
}
