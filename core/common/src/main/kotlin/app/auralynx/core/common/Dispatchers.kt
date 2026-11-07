package app.auralynx.core.common

import javax.inject.Qualifier

/** Qualifier for the coroutine dispatcher used for blocking I/O (sockets, files, database). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Qualifier for the CPU-bound dispatcher (DSP, parsing, analytics). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Qualifier for a process-lifetime [kotlinx.coroutines.CoroutineScope] backed by a SupervisorJob. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
