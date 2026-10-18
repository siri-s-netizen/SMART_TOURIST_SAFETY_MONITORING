package com.touristsafety.app.testing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.touristsafety.app.data.database.AppDatabase
import com.touristsafety.app.data.database.DatabaseMigrations

/** An in-memory [AppDatabase] for the Robolectric-backed tests. Closed by the caller. */
fun inMemoryDatabase(): AppDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        // The pipeline is driven from the test thread on purpose; this is a test-only concession.
        .allowMainThreadQueries()
        .build()
}

/**
 * An in-memory database whose Room work runs on [executor] instead of the shared default pool.
 *
 * Room's default query/transaction executors come from `ArchTaskExecutor` and are **process-wide**, so
 * every database built in the suite queues behind the same handful of threads. Room also does not shut
 * them down when a database is closed, so a query that is stalled or leaked by one test class can block
 * an unrelated one — which is how the SOS tests behaved: green when their class ran alone, red inside a
 * full run. A pool the test owns makes it hermetic.
 *
 * The caller owns [executor] and must shut it down; Room deliberately does not.
 */
fun inMemoryDatabase(executor: java.util.concurrent.Executor): AppDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(executor)
        .setTransactionExecutor(executor)
        .build()
}

/**
 * A private, named thread pool.
 *
 * `Dispatchers.Default` is a JVM-wide singleton sized to the core count, and on a machine already low on
 * free RAM a full-suite run can leave those threads occupied by unrelated work. A test that must observe
 * genuinely asynchronous progress under a real-time ceiling should not depend on them.
 */
fun privateExecutor(name: String, threads: Int = 2): java.util.concurrent.ExecutorService =
    java.util.concurrent.Executors.newFixedThreadPool(threads) { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

/**
 * A file-backed [AppDatabase], so a test can close it, reopen it and prove that saved zones and alert
 * history really survived — the exact guarantee the project's migration strategy exists to protect.
 */
fun fileDatabase(name: String): AppDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return Room.databaseBuilder(context, AppDatabase::class.java, name)
        .allowMainThreadQueries()
        .addMigrations(*DatabaseMigrations.ALL)
        .build()
}

fun deleteDatabaseFile(name: String) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.deleteDatabase(name)
}

/**
 * Polls until [condition] holds.
 *
 * Needed because the production coroutine scopes hop onto Room's own executors, which the test
 * scheduler cannot see. Real-time polling keeps those tests honest instead of papering over the
 * asynchrony with fixed sleeps.
 *
 * This deliberately does **not** drain the main looper. An earlier version called
 * `ShadowLooper.idleMainLooper()` on every iteration, on the theory that Room posts its invalidation
 * callbacks there. Two things were wrong with that:
 *
 *  * it is unnecessary — Room posts invalidation refreshes to its own query executor, so a collected
 *    `Flow` updates on a background thread with no help from the main looper. Measured directly: the
 *    outbox row reaches `SENT` and the collected `Flow` reports it within ~125 ms with no draining;
 *  * it is actively harmful — called from the main thread inside `runBlocking`, `idleMainLooper()`
 *    cost roughly 1.5 s per call and starved the background work being waited for, which is exactly
 *    why the SOS delivery test failed intermittently when the whole suite ran (a 60 s wait spent
 *    almost all of its time inside the drain loop) and passed when run alone.
 *
 * A simple real-time poll against a generous ceiling is both faster and more honest, so the timeout
 * is still a ceiling on a genuinely asynchronous operation rather than a substitute for one.
 */
fun awaitUntil(
    timeoutMillis: Long = 5_000,
    intervalMillis: Long = 10,
    message: String = "condition was not met in time",
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(intervalMillis)
    }
    check(condition()) { message }
}
