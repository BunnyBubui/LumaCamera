package th.ogay.luma.ai

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException

/** A preview and a full export must not allocate two neural activation arenas. */
object DetailComputeGate {
    private val gate = Semaphore(1,true)
    fun <T> run(stopped: () -> Boolean, action: () -> T): T {
        while (!gate.tryAcquire(200,TimeUnit.MILLISECONDS)) if (stopped()) throw CancellationException()
        try { if (stopped()) throw CancellationException(); return action() }
        finally { gate.release() }
    }
}
