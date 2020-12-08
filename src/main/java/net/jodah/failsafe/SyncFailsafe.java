package net.jodah.failsafe;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;

import net.jodah.failsafe.Callables.ContextualCallableWrapper;
import net.jodah.failsafe.function.CheckedRunnable;
import net.jodah.failsafe.function.ContextualCallable;
import net.jodah.failsafe.function.ContextualRunnable;
import net.jodah.failsafe.internal.util.Assert;
import net.jodah.failsafe.util.concurrent.Scheduler;
import net.jodah.failsafe.util.concurrent.Schedulers;

/**
 * Performs synchronous executions according to a {@link RetryPolicy} and {@link CircuitBreaker}.
 * 
 * @author Jonathan Halterman
 */
public class SyncFailsafe {
  private RetryPolicy retryPolicy = RetryPolicy.NEVER;
  private CircuitBreaker circuitBreaker;
  private Listeners<?> listeners;

  SyncFailsafe(RetryPolicy retryPolicy) {
    this.retryPolicy = retryPolicy;
  }

  SyncFailsafe(CircuitBreaker circuitBreaker) {
    this.circuitBreaker = circuitBreaker;
  }

  /**
   * Executes the {@code callable} until a successful result is returned or the configured {@link RetryPolicy} is
   * exceeded.
   * 
   * @throws NullPointerException if the {@code callable} is null
   * @throws FailsafeException if the {@code callable} fails with a Throwable and the retry policy is exceeded, or if
   *           interrupted while waiting to perform a retry.
   * @throws CircuitBreakerOpenException if a configured circuit is open.
   */
  public <T> T get(Callable<T> callable) {
    return call(Assert.notNull(callable, "callable"));
  }

  /**
   * Executes the {@code callable} until a successful result is returned or the configured {@link RetryPolicy} is
   * exceeded.
   * 
   * @throws NullPointerException if the {@code callable} is null
   * @throws FailsafeException if the {@code callable} fails with a Throwable and the retry policy is exceeded, or if
   *           interrupted while waiting to perform a retry.
   * @throws CircuitBreakerOpenException if a configured circuit is open.
   */
  public <T> T get(ContextualCallable<T> callable) {
    return call(Callables.of(callable));
  }

  /**
   * Executes the {@code runnable} until successful or until the configured {@link RetryPolicy} is exceeded.
   * 
   * @throws NullPointerException if the {@code runnable} is null
   * @throws FailsafeException if the {@code callable} fails with a Throwable and the retry policy is exceeded, or if
   *           interrupted while waiting to perform a retry.
   * @throws CircuitBreakerOpenException if a configured circuit is open.
   */
  public void run(CheckedRunnable runnable) {
    call(Callables.of(runnable));
  }

  /**
   * Executes the {@code runnable} until successful or until the configured {@link RetryPolicy} is exceeded.
   * 
   * @throws NullPointerException if the {@code runnable} is null
   * @throws FailsafeException if the {@code callable} fails with a Throwable and the retry policy is exceeded, or if
   *           interrupted while waiting to perform a retry.
   * @throws CircuitBreakerOpenException if a configured circuit is open.
   */
  public void run(ContextualRunnable runnable) {
    call(Callables.of(runnable));
  }

  /**
   * Configures the {@code circuitBreaker} to be used to control the rate of event execution.
   * 
   * @throws NullPointerException if {@code circuitBreaker} is null
   * @throws IllegalStateException if a circuit breaker is already configured
   */
  public SyncFailsafe with(CircuitBreaker circuitBreaker) {
    Assert.state(this.circuitBreaker == null, "A circuit breaker has already been configured");
    this.circuitBreaker = Assert.notNull(circuitBreaker, "circuitBreaker");
    return this;
  }

  /**
   * Configures the {@code retryPolicy} to be used for retrying failed executions.
   * 
   * @throws NullPointerException if {@code retryPolicy} is null
   * @throws IllegalStateException if a retry policy is already configured
   */
  public SyncFailsafe with(RetryPolicy retryPolicy) {
    Assert.state(this.retryPolicy == RetryPolicy.NEVER, "A retry policy has already been configured");
    this.retryPolicy = Assert.notNull(retryPolicy, "retryPolicy");
    return this;
  }

  /**
   * Configures the {@code listeners} to be called as execution events occur.
   * 
   * @throws NullPointerException if {@code listeners} is null
   */
  public SyncFailsafe with(Listeners<?> listeners) {
    this.listeners = Assert.notNull(listeners, "listeners");
    return this;
  }

  /**
   * Creates and returns a new AsyncFailsafe instance that will perform executions and retries asynchronously via the
   * {@code executor}.
   * 
   * @throws NullPointerException if {@code executor} is null
   */
  public AsyncFailsafe with(ScheduledExecutorService executor) {
    return new AsyncFailsafe(retryPolicy, circuitBreaker, Schedulers.of(executor), listeners);
  }

  /**
   * Creates and returns a new AsyncFailsafe instance that will perform executions and retries asynchronously via the
   * {@code scheduler}.
   * 
   * @throws NullPointerException if {@code scheduler} is null
   */
  public AsyncFailsafe with(Scheduler scheduler) {
    return new AsyncFailsafe(retryPolicy, circuitBreaker, Assert.notNull(scheduler, "scheduler"), listeners);
  }

  /**
   * Calls the {@code callable} synchronously, performing retries according to the {@code retryPolicy}.
   * 
   * @throws FailsafeException if the {@code callable} fails with a Throwable and the retry policy is exceeded or if
   *           interrupted while waiting to perform a retry
   * @throws CircuitBreakerOpenException if a configured circuit breaker is open
   */
  @SuppressWarnings("unchecked")
  private <T> T call(Callable<T> callable) {
    Execution execution = null;
    if (circuitBreaker == null)
      execution = new Execution(retryPolicy);
    else {
      circuitBreaker.initialize();
      execution = new Execution(retryPolicy, circuitBreaker);
    }

    // Handle contextual calls
    if (callable instanceof ContextualCallableWrapper)
      ((ContextualCallableWrapper<T>) callable).inject(execution);

    Listeners<T> typedListeners = (Listeners<T>) listeners;
    T result = null;
    Throwable failure;

    while (true) {
      if (circuitBreaker != null && !circuitBreaker.allowsExecution())
        throw new CircuitBreakerOpenException();

      try {
        execution.before();
        failure = null;
        result = callable.call();
      } catch (Throwable t) {
        failure = t;
      }

      // Attempt to complete execution
      boolean complete = execution.complete(result, failure, true);

      // Handle failure
      if (!execution.success && typedListeners != null)
        typedListeners.handleFailedAttempt(result, failure, execution, null);

      if (complete) {
        if (typedListeners != null)
          typedListeners.complete(result, failure, execution, execution.success);
        if (execution.success || failure == null)
          return result;
        FailsafeException re = failure instanceof FailsafeException ? (FailsafeException) failure
            : new FailsafeException(failure);
        throw re;
      } else {
        try {
          Thread.sleep(execution.getWaitTime().toMillis());
        } catch (InterruptedException e) {
          throw new FailsafeException(e);
        }

        if (typedListeners != null)
          typedListeners.handleRetry(result, failure, execution, null);
      }
    }
  }
}