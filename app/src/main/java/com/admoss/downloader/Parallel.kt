package com.admoss.downloader

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** Runs [transform] on several threads at once and returns the results in the original order. */
fun <T, R> List<T>.parallelMap(maxThreads: Int = 4, transform: (T) -> R): List<R> {
    if (size <= 1 || maxThreads <= 1) return map(transform)
    val pool = Executors.newFixedThreadPool(minOf(maxThreads, size))
    try {
        val futures = map { item -> pool.submit(Callable { transform(item) }) }
        return futures.map {
            try {
                it.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    } finally {
        pool.shutdownNow()
    }
}
