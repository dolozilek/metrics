package com.avast.metrics.core.multi;

import com.avast.metrics.api.Timer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Timer used by {@link MultiMonitor}.
 */
class MultiTimer implements Timer {
    private final List<Timer> timers;

    public MultiTimer(List<Timer> timers) {
        if (timers.size() < 2) {
            throw new IllegalArgumentException("Multi timer from less than 2 timers makes no sense");
        }

        this.timers = timers;
    }

    @Override
    public TimeContext start() {
        List<TimeContext> contexts = timers.stream()
                .map(Timer::start)
                .collect(Collectors.toList());

        return new MultiContext(contexts);
    }

    @Override
    public void update(Duration duration) {
        timers.forEach(t -> t.update(duration));
    }

    @Override
    public <T> T time(Callable<T> operation) throws Exception {
        try (TimeContext ignored = this.start()) {
            return operation.call();
        }
    }

    @Override
    public <T> T time(Callable<T> operation, Timer failureTimer) throws Exception {
        TimeContext successContext = start();
        TimeContext failureContext = failureTimer.start();
        try {
            T result = operation.call();
            successContext.stop();
            return result;
        } catch (Exception ex) {
            failureContext.stop();
            throw ex;
        }
    }

    @Override
    public <T> CompletableFuture<T> timeAsync(Callable<CompletableFuture<T>> operation, Executor executor) throws Exception {
        TimeContext context = start();
        try {
            CompletableFuture<T> promise = new CompletableFuture<>();
            CompletableFuture<T> future = operation.call();
            future.handleAsync((success, failure) -> {
                context.stop();
                if (failure == null) {
                    promise.complete(success);
                } else {
                    promise.completeExceptionally(failure);
                }
                return null;
            }, executor);
            return promise;
        } catch (Exception ex) {
            context.stop();
            throw ex;
        }
    }

    @Override
    public <T> CompletableFuture<T> timeAsync(Callable<CompletableFuture<T>> operation, Timer failureTimer, Executor executor) throws Exception {
        TimeContext successContext = start();
        TimeContext failureContext = failureTimer.start();
        try {
            CompletableFuture<T> promise = new CompletableFuture<>();
            CompletableFuture<T> future = operation.call();
            future.handleAsync((success, failure) -> {
                if (failure == null) {
                    successContext.stop();
                    promise.complete(success);
                } else {
                    failureContext.stop();
                    promise.completeExceptionally(failure);
                }
                return null;
            }, executor);
            return promise;
        } catch (Exception ex) {
            failureContext.stop();
            throw ex;
        }
    }

    @Override
    public long count() {
        return timers.get(0).count();
    }

    @Override
    public String getName() {
        return timers.get(0).getName();
    }

    private static class MultiContext implements TimeContext {
        private final List<TimeContext> contexts;

        public MultiContext(List<TimeContext> contexts) {
            this.contexts = contexts;
        }

        @Override
        public void stop() {
            contexts.forEach(TimeContext::stop);
        }
    }
}