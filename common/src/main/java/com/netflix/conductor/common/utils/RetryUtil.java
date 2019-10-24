/**
 * Copyright 2018 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.conductor.common.utils;

import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static java.lang.String.format;

/**
 * Utility class that deals with retries in case of transient failures.
 *
 * <b>Note:</b>
 * Create a new {@link RetryUtil} for every operation that needs to retried for the stated retries.
 *
 * <b>Limitations:</b>
 * <ul>
 * <li>
 * The current implementation does not make a distinction between transient and non transient errors.
 * There is no categorization of transient and non transient failure in Conductor.
 * Once the exception hierarchy is available in Conductor, this class implementation can be changed to
 * short circuit the non transient errors.
 * </li>
 * <li>
 * The retry limit is not configurable and is hard coded to 3
 * </li>
 * </ul>
 *
 * @param <T> The type of the object that will be returned by the flaky supplier function
 */
public class RetryUtil<T> {

    private static final Logger logger = LoggerFactory.getLogger(RetryUtil.class);

    private AtomicInteger internalNumberOfRetries = new AtomicInteger();

    /**
     * A helper method which has the ability to execute a flaky supplier function and retry in case of failures.
     *
     * @param supplierCommand:      Any function that is flaky and needs multiple retries.
     * @param throwablePredicate:   A {@link Predicate} housing the exceptional
     *                              criteria to perform informed filtering before retrying.
     * @param resultRetryPredicate: a predicate to be evaluated for a valid condition of the expected result
     * @param maxAttempts:           Number of times the function is to be retried before failure
     * @param shortDescription:     A short description of the function that will be used in logging and error propagation.
     *                              The intention of this description is to provide context for Operability.
     * @param operationName:        The name of the function for traceability in logs
     * @return an instance of return type of the supplierCommand
     * @throws RuntimeException in case of failed attempts to get T, which needs to be returned by the supplierCommand.
     *                          The instance of the returned exception has:
     *                          <ul>
     *                          <li>A message with shortDescription and operationName with the number of retries made</li>
     *                          <li>And a reference to the original exception generated during the last {@link Attempt} of the retry</li>
     *                          </ul>
     */
    public T retryOnException(Supplier<T> supplierCommand,
                              Predicate<Throwable> throwablePredicate,
                              Predicate<T> resultRetryPredicate,
                              int maxAttempts,
                              String shortDescription, String operationName) throws RuntimeException {

        RetryPolicy<T> retryPolicy = new RetryPolicy<T>()
                .handleIf(Optional.ofNullable(throwablePredicate).orElse(exception -> true))
                .handleResultIf(Optional.ofNullable(resultRetryPredicate).orElse(result -> false))
                .withBackoff(1, 90, ChronoUnit.SECONDS)
                .withJitter(Duration.ofMillis(500))
                .withMaxAttempts(maxAttempts)
                .onFailedAttempt(attempt -> {
                    logger.debug("Attempt # {}, {} millis since first attempt. Operation: {}, description:{}",
                            attempt.getAttemptCount(), attempt.getElapsedTime().toMillis(), operationName, shortDescription);
                    internalNumberOfRetries.incrementAndGet();
                })
                .onRetriesExceeded(retryException -> {
                    String errorMessage = format("Operation '%s:%s' failed after retrying %d times, retry limit %d", operationName,
                            shortDescription, internalNumberOfRetries.get(), maxAttempts);
                    logger.error(errorMessage, retryException.getFailure());
                });

        try {
            return Failsafe.with(retryPolicy).get(supplierCommand::get);
        } catch (RuntimeException executionException) {
            String errorMessage;
            if (internalNumberOfRetries.get() >= maxAttempts - 1) {
                errorMessage = format("Operation '%s:%s' failed after retrying %d times, retry limit %d", operationName,
                        shortDescription, internalNumberOfRetries.get(), maxAttempts);
            } else {
                errorMessage = format("Operation '%s:%s' failed for the %d time in RetryUtil", operationName,
                        shortDescription, internalNumberOfRetries.get());
            }
            logger.debug(errorMessage);
            System.out.println(errorMessage);
            throw new RuntimeException(errorMessage, executionException);
        }
    }
}
