/*
 * Copyright (c) 2022, 2025, 2026 Contributors to the Eclipse Foundation.
 * Copyright (c) 2024 Payara Foundation and/or its affiliates.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */
package org.glassfish.concurro.cdi.asynchronous;

import jakarta.annotation.Priority;
import jakarta.enterprise.concurrent.Asynchronous;
import jakarta.enterprise.concurrent.CronTrigger;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.enterprise.concurrent.Schedule;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.lang.System.Logger;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.glassfish.concurro.internal.AsynchronousScheduledAction;
import org.glassfish.concurro.internal.CompoundTrigger;
import org.glassfish.concurro.internal.ManagedCompletableFuture;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.ERROR;

/**
 * Interceptor for @Asynchronous.
 *
 * @author Petr Aubrecht &lt;aubrecht@asoftware.cz&gt;
 */
@Interceptor
@Asynchronous
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 5)
public class AsynchronousInterceptor {
    private static final Logger LOG = System.getLogger(AsynchronousInterceptor.class.getName());

    @AroundInvoke
    public Object intercept(InvocationContext context) throws Exception {
        Method method = context.getMethod();
        Asynchronous asynchAnnotation = context.getInterceptorBinding(Asynchronous.class);

        if (asynchAnnotation.runAt().length > 0) {
            return schedule(context, method, asynchAnnotation);
        }
        return executeDirectly(context, method, asynchAnnotation);
    }

    private CompletableFuture<Object> executeDirectly(InvocationContext context, Method method, Asynchronous asynchAnnotation) {
        String executor = asynchAnnotation.executor();
        executor = executor != null ? executor : "java:comp/DefaultManagedExecutorService"; // provide default value if there is none
        LOG.log(DEBUG, "AsynchronousInterceptor.intercept around asynchronous method {0}, executor=''{1}''", method, executor);
        ManagedExecutorService mes = lookupMES(ManagedExecutorService.class, executor, method.getName());
        CompletableFuture<Object> resultFuture = new ManagedCompletableFuture<>(mes);
        mes.submit(() -> {
            Asynchronous.Result.setFuture(resultFuture);
            try {
                // The asynchronous method either completes Asynchronous.Result and returns it,
                // or returns a different CompletableFuture instance.
                CompletableFuture<Object> returnedFuture = (CompletableFuture<Object>) context.proceed();
                completeResultFuture(method, resultFuture, returnedFuture);
            } catch (Exception ex) {
                resultFuture.completeExceptionally(ex);
            } finally {
                // cleanup after asynchronous call
                Asynchronous.Result.setFuture(null);
            }
        });
        return resultFuture;
    }

    /**
     * Bridges the future returned by an {@code @Asynchronous} method to the {@code resultFuture}
     * handed to the caller.
     * <p>
     * Two cases are supported per the Jakarta Concurrency specification:
     * <ul>
     * <li>The method completes {@link Asynchronous.Result} and returns it (the same instance as
     * {@code resultFuture}). In that case the result future is already completed; if it is not, the
     * method forgot to call {@code Asynchronous.Result.complete()} and the result future is
     * cancelled.</li>
     * <li>The method returns a <em>different</em> {@link CompletableFuture}. Its completion (value or
     * exception) is propagated to {@code resultFuture} without blocking the executor thread.</li>
     * </ul>
     *
     * @param method the intercepted method, used for diagnostics only
     * @param resultFuture the future returned to the caller (also bound to {@link Asynchronous.Result})
     * @param returnedFuture the future returned by the method body
     */
    static void completeResultFuture(Method method, CompletableFuture<Object> resultFuture,
            CompletableFuture<Object> returnedFuture) {
        if (returnedFuture == resultFuture) {
            // The method was expected to complete Asynchronous.Result itself.
            if (!returnedFuture.isDone()) {
                LOG.log(ERROR,
                    "Method annotated with @Asynchronous did not call Asynchronous.Result.complete() at its end: {0}",
                    method);
                resultFuture.cancel(true);
            }
        } else {
            // The method returned a different future; complete resultFuture when it completes.
            returnedFuture.whenComplete((value, throwable) -> {
                if (throwable != null) {
                    resultFuture.completeExceptionally(throwable);
                } else {
                    resultFuture.complete(value);
                }
            });
        }
    }

    private static <T> T lookupMES(Class<T> cls, String executor, String methodName) throws RejectedExecutionException {
        T mes;
        try {
            Object lookupMes = new InitialContext().lookup(executor);
            if (lookupMes == null) {
                throw new RejectedExecutionException("ManagedExecutorService with jndi '" + executor + "' not found!");
            }
            if (!(cls.isInstance(lookupMes))) {
                throw new RejectedExecutionException("ManagedExecutorService with jndi '" + executor + "' must be of type " + cls + ", found " + lookupMes.getClass().getName());
            }
            mes = (T) lookupMes;
        } catch (NamingException ex) {
            throw new RejectedExecutionException("ManagedExecutorService with jndi '" + executor + "' not found as requested by asynchronous method " + methodName);
        }
        return mes;
    }

    public CompletableFuture<Object> schedule(InvocationContext context, Method method, Asynchronous asynchAnnotation) {

        // FIXME: challenge testScheduledAsynchWithInvalidJNDIName from TCK and then remove this useless block
        // For runAt, executor is not used at all!
        // check existence of executor
        String executor = asynchAnnotation.executor();
        executor = executor != null ? executor : "java:comp/DefaultManagedExecutorService"; // provide default value if there is none
        lookupMES(ManagedExecutorService.class, executor, method.getName());

        ManagedScheduledExecutorService mses = lookupMES(ManagedScheduledExecutorService.class, "java:comp/DefaultManagedScheduledExecutorService", method.getName());
        CompletableFuture<Object> future = mses.newIncompleteFuture();
        CompoundTrigger compoundTrigger = new CompoundTrigger(mses);

        for (Schedule schedule : asynchAnnotation.runAt()) {
            long skipIfLateBySeconds = schedule.skipIfLateBy();
            ZoneId zone = schedule.zone().isEmpty() ? ZoneId.systemDefault() : ZoneId.of(schedule.zone());
            CronTrigger trigger = getCronTrigger(schedule, zone);
            compoundTrigger.addTrigger(trigger, skipIfLateBySeconds);
        }
        AsynchronousScheduledAction action = new AsynchronousScheduledAction(context, future);
        final ScheduledFuture<?> scheduledFuture = mses.schedule(action, compoundTrigger);
        action.setScheduledFuture(scheduledFuture);
        return future;
    }

    static final CronTrigger getCronTrigger(Schedule schedule, ZoneId zone) {
        if (schedule.cron().isEmpty()) {
            var trigger = new CronTrigger(zone);
            setIfNotEmpty(trigger::seconds, schedule.seconds());
            setIfNotEmpty(trigger::minutes, schedule.minutes());
            setIfNotEmpty(trigger::hours, schedule.hours());
            setIfNotEmpty(trigger::daysOfWeek, schedule.daysOfWeek());
            setIfNotEmpty(trigger::daysOfMonth, schedule.daysOfMonth());
            setIfNotEmpty(trigger::months, schedule.months());
            return trigger;
        }
        return new CronTrigger(schedule.cron(), zone);
    }

    static final void setIfNotEmpty(Consumer<int[]> consumer, int[] data) {
        Optional.ofNullable(data)
                .filter(a -> a.length > 0)
                .ifPresent(consumer);
    }

    static final <T> void setIfNotEmpty(Consumer<T[]> consumer, T[] data) {
        Optional.ofNullable(data)
                .filter(a -> a.length > 0)
                .ifPresent(consumer);
    }
}
