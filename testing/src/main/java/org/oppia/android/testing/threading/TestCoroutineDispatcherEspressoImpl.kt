package org.oppia.android.testing.threading

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay as delayInScope // Needed to avoid conflict with Delay.delay().

/**
 * Espresso-specific implementation of [TestCoroutineDispatcher].
 *
 * This class forwards all execution immediately to the backing [CoroutineDispatcher] since it
 * assumes all tasks are running with a real-time clock to mimic production behavior. This
 * implementation also tracks the running state of tasks in order to support Espresso-specific
 * idling resources (though it's up to the caller of this class to actually hook up an idling
 * resource for this purpose).
 */
@OptIn(InternalCoroutinesApi::class)
class TestCoroutineDispatcherEspressoImpl private constructor(
  private val realCoroutineDispatcher: CoroutineDispatcher
) : TestCoroutineDispatcher(), Delay {

  private val realCoroutineScope by lazy { CoroutineScope(realCoroutineDispatcher) }
  private val executingTaskCount = AtomicInteger(0)
  private val totalTaskCount = AtomicInteger(0)
  /** Map of task ID (based on [totalTaskCount]) to the time in millis when that task will run. */
  private val taskCompletionTimes = ConcurrentHashMap<Int, Long>()
  private var taskIdleListener: TaskIdleListener? = null

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    val taskId = totalTaskCount.incrementAndGet()
    taskCompletionTimes[taskId] = System.currentTimeMillis()

    // Tasks immediately will start running, so track the task immediately.
    executingTaskCount.incrementAndGet()
    notifyIfRunning()
    realCoroutineDispatcher.dispatch(context) {
      try {
        block.run()
      } finally {
        executingTaskCount.decrementAndGet()
        taskCompletionTimes.remove(taskId)
      }
      notifyIfIdle()
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  override fun scheduleResumeAfterDelay(
    timeMillis: Long,
    continuation: CancellableContinuation<Unit>
  ) {
    val taskId = totalTaskCount.incrementAndGet()
    taskCompletionTimes[taskId] = System.currentTimeMillis() + timeMillis
    val block: CancellableContinuation<Unit>.() -> Unit = {
      realCoroutineDispatcher.resumeUndispatched(Unit)
    }

    // Treat the continuation as a delayed dispatch. Even though it's executing in the future, it
    // should be assumed to be 'running' since the dispatcher is executing tasks in real-time.
    executingTaskCount.incrementAndGet()
    notifyIfRunning()
    val delayResult = realCoroutineScope.async {
      delayInScope(timeMillis)
    }
    delayResult.invokeOnCompletion {
      try {
        continuation.block()
      } finally {
        executingTaskCount.decrementAndGet()
        taskCompletionTimes.remove(taskId)
      }
      notifyIfIdle()
    }
  }

  override fun runCurrent(timeout: Long, timeoutUnit: TimeUnit) {
    // Nothing to do; the queue is always continuously running.
  }

  override fun hasPendingTasks(): Boolean = executingTaskCount.get() != 0

  override fun getNextFutureTaskCompletionTimeMillis(timeMillis: Long): Long? {
    // Find the next most recent task completion time that's after the specified time.
    return taskCompletionTimes.values.filter { it > timeMillis }.minOrNull()
  }

  override fun hasPendingCompletableTasks(): Boolean {
    // Any pending tasks are always considered completable since the dispatcher runs in real-time.
    return hasPendingTasks()
  }

  override fun setTaskIdleListener(taskIdleListener: TaskIdleListener) {
    this.taskIdleListener = taskIdleListener
    if (executingTaskCount.get() > 0) {
      notifyIfRunning()
    } else {
      notifyIfIdle()
    }
  }

  private fun notifyIfRunning() {
    taskIdleListener?.takeIf { executingTaskCount.get() > 0 }?.onDispatcherRunning()
  }

  private fun notifyIfIdle() {
    taskIdleListener?.takeIf { executingTaskCount.get() == 0 }?.onDispatcherIdle()
  }

  /**
   * Injectable implementation of [TestCoroutineDispatcher.Factory] for
   * [TestCoroutineDispatcherEspressoImpl].
   */
  class FactoryImpl @Inject constructor() : Factory {
    override fun createDispatcher(realDispatcher: CoroutineDispatcher): TestCoroutineDispatcher {
      return TestCoroutineDispatcherEspressoImpl(realDispatcher)
    }
  }
}
