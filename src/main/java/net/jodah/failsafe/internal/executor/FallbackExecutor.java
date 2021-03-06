/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package net.jodah.failsafe.internal.executor;

import net.jodah.failsafe.ExecutionResult;
import net.jodah.failsafe.FailsafeFuture;
import net.jodah.failsafe.Fallback;
import net.jodah.failsafe.PolicyExecutor;
import net.jodah.failsafe.util.concurrent.Scheduler;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * A PolicyExecutor that handles failures according to a fallback.
 */
public class FallbackExecutor extends PolicyExecutor<Fallback> {
  public FallbackExecutor(Fallback fallback) {
    super(fallback);
  }

  @Override
  @SuppressWarnings("unchecked")
  protected ExecutionResult onFailure(ExecutionResult result) {
    try {
      return result.withResult(policy.apply(result.getResult(), result.getFailure(), execution.copy()));
    } catch (Exception e) {
      return ExecutionResult.failure(e);
    }
  }

  @SuppressWarnings("unchecked")
  protected CompletableFuture<ExecutionResult> onFailureAsync(ExecutionResult result, Scheduler scheduler,
      FailsafeFuture<Object> future) {
    if (!policy.isAsync())
      return CompletableFuture.completedFuture(onFailure(result));

    CompletableFuture<ExecutionResult> promise = new CompletableFuture<>();
    Callable<Object> callable = () -> promise.complete(onFailure(result));

    try {
      future.inject((Future) scheduler.schedule(callable, result.getWaitNanos(), TimeUnit.NANOSECONDS));
    } catch (Exception e) {
      promise.completeExceptionally(e);
    }

    return promise;
  }
}
