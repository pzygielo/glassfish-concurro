/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsynchronousInterceptorTest {

    /**
     * Regression test for <a href="https://github.com/eclipse-ee4j/glassfish/issues/24203">#24203</a>:
     * when an {@code @Asynchronous} method returns a <em>different</em>, not-yet-completed
     * CompletableFuture, the result future handed to the caller must not be cancelled. It must be
     * completed with the same result once the returned future completes.
     */
    @Test
    public void differentNotYetCompletedFutureIsBridgedNotCancelled() throws Exception {
        CompletableFuture<Object> resultFuture = new CompletableFuture<>();
        CompletableFuture<Object> returnedFuture = new CompletableFuture<>();

        AsynchronousInterceptor.completeResultFuture(anyMethod(), resultFuture, returnedFuture);

        // The returned future is still running: the result future must stay pending, not be cancelled.
        assertFalse(resultFuture.isDone(), "result future must not be completed yet");
        assertFalse(resultFuture.isCancelled(), "result future must not be cancelled");

        returnedFuture.complete("hello");

        assertEquals("hello", resultFuture.get(5, SECONDS));
        assertFalse(resultFuture.isCancelled());
    }

    @Test
    public void differentFutureExceptionIsPropagated() {
        CompletableFuture<Object> resultFuture = new CompletableFuture<>();
        CompletableFuture<Object> returnedFuture = new CompletableFuture<>();

        AsynchronousInterceptor.completeResultFuture(anyMethod(), resultFuture, returnedFuture);

        IllegalStateException failure = new IllegalStateException("boom");
        returnedFuture.completeExceptionally(failure);

        ExecutionException thrown = assertThrows(ExecutionException.class, resultFuture::get);
        assertSame(failure, thrown.getCause());
        assertFalse(resultFuture.isCancelled());
    }

    @Test
    public void alreadyCompletedDifferentFutureCompletesResult() throws Exception {
        CompletableFuture<Object> resultFuture = new CompletableFuture<>();
        CompletableFuture<Object> returnedFuture = CompletableFuture.completedFuture("done");

        AsynchronousInterceptor.completeResultFuture(anyMethod(), resultFuture, returnedFuture);

        assertEquals("done", resultFuture.get(5, SECONDS));
    }

    @Test
    public void sameFutureCompletedByMethodIsLeftIntact() throws Exception {
        CompletableFuture<Object> resultFuture = new CompletableFuture<>();
        resultFuture.complete("via Asynchronous.Result");

        AsynchronousInterceptor.completeResultFuture(anyMethod(), resultFuture, resultFuture);

        assertEquals("via Asynchronous.Result", resultFuture.get(5, SECONDS));
        assertFalse(resultFuture.isCancelled());
    }

    @Test
    public void sameFutureNotCompletedByMethodIsCancelled() {
        CompletableFuture<Object> resultFuture = new CompletableFuture<>();

        AsynchronousInterceptor.completeResultFuture(anyMethod(), resultFuture, resultFuture);

        // The method forgot to call Asynchronous.Result.complete(): the result future is cancelled.
        assertTrue(resultFuture.isCancelled());
    }

    private static java.lang.reflect.Method anyMethod() {
        // Any method works: it is only used for diagnostic logging.
        return AsynchronousInterceptorTest.class.getDeclaredMethods()[0];
    }

    @Test
    public void testGettingCronTriggerFromScheduleWithCronExpression() {
        var scheduleWithCronExpression = ScheduleStub.newScheduleWithCronExpression("*/5 * * * * *");

        var trigger = AsynchronousInterceptor.getCronTrigger(scheduleWithCronExpression, null);

        var representation = trigger.toString();
        assert representation.matches("CronTrigger@.* seconds 0,5,10,15,20,25,30,35,40,45,50,55, \\* \\* \\* \\* \\*") : representation;
    }

    @Test
    public void testGettingCronTriggerFromSchedule() {
        var scheduleWithDefaults = ScheduleStub.newScheduleWithDefaults();

        var trigger = AsynchronousInterceptor.getCronTrigger(scheduleWithDefaults, null);

        var representation = trigger.toString();
        assert representation.matches("CronTrigger@.* seconds 0, minutes 0, hours 0, \\* \\* \\*") : representation;
    }
}
