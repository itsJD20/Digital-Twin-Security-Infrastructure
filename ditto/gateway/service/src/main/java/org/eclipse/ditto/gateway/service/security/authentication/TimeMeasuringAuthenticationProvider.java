/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.gateway.service.security.authentication;

import static org.eclipse.ditto.base.model.common.ConditionChecker.checkNotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import javax.annotation.concurrent.Immutable;

import org.apache.pekko.http.javadsl.server.RequestContext;
import org.eclipse.ditto.base.model.auth.AuthorizationContextType;
import org.eclipse.ditto.base.model.auth.AuthorizationSubject;
import org.eclipse.ditto.base.model.common.HttpStatus;
import org.eclipse.ditto.base.model.exceptions.DittoRuntimeException;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.gateway.api.GatewayAuthenticationProviderUnavailableException;
import org.eclipse.ditto.internal.utils.metrics.instruments.timer.StartedTimer;
import org.eclipse.ditto.internal.utils.pekko.logging.ThreadSafeDittoLogger;
import org.eclipse.ditto.internal.utils.tracing.DittoTracing;
import org.eclipse.ditto.internal.utils.tracing.TraceUtils;
import org.eclipse.ditto.internal.utils.tracing.span.SpanTagKey;
import org.eclipse.ditto.internal.utils.tracing.span.StartedSpan;

/**
 * An abstract class that measures the time it takes to authenticate a request.
 *
 * @param <R> the type of the AuthenticationResult implementation.
 */
@Immutable
public abstract class TimeMeasuringAuthenticationProvider<R extends AuthenticationResult>
        implements AuthenticationProvider<R> {

    private final ThreadSafeDittoLogger logger;

    /**
     * Constructs a new TimeMeasuringAuthenticationProvider object.
     *
     * @param logger the logger to be used.
     * @throws NullPointerException if {@code logger} is {@code null}.
     */
    protected TimeMeasuringAuthenticationProvider(final ThreadSafeDittoLogger logger) {
        this.logger = checkNotNull(logger, "logger");
    }

    @Override
    public final CompletableFuture<R> authenticate(final RequestContext requestContext,
            final DittoHeaders dittoHeaders) {
        final AuthorizationContextType authorizationContextType = getType(requestContext);
        final StartedTimer timer = TraceUtils.newAuthFilterTimer(authorizationContextType, requestContext.getRequest())
                .start();
        final StartedSpan startedSpan = DittoTracing.newStartedSpanByTimer(dittoHeaders, timer);
        final DittoHeaders propagatedHeaders = DittoHeaders.of(startedSpan.propagateContext(dittoHeaders));

        CompletableFuture<R> resultFuture;
        try {
            resultFuture = tryToAuthenticate(requestContext, propagatedHeaders);
        } catch (final Throwable e) {
            resultFuture = CompletableFuture.failedFuture(e);
        }
        resultFuture = resultFuture.thenApply(
                        authenticationResult -> {
                            timer.tag(SpanTagKey.AUTH_SUCCESS.getTagForValue(authenticationResult.isSuccess()));
                            startedSpan.tag(SpanTagKey.AUTH_SUBJECTS.getTagForValue(
                                    authenticationResult.getAuthorizationContext().getAuthorizationSubjects().stream()
                                            .map(AuthorizationSubject::getId)
                                            .collect(Collectors.joining(","))
                            ));
                            return authenticationResult;
                        })
                .exceptionally(error -> {
                    final Throwable rootCause = getRootCause(error);
                    startedSpan.tagAsFailed(rootCause);
                    if (rootCause instanceof DittoRuntimeException dittoRuntimeException) {
                        timer.tag(SpanTagKey.AUTH_SUCCESS.getTagForValue(false));
                        if (isInternalError(dittoRuntimeException.getHttpStatus())) {
                            logger.withCorrelationId(propagatedHeaders)
                                    .warn("An unexpected error occurred during authentication of type <{}>.",
                                            authorizationContextType, dittoRuntimeException);
                            timer.tag(SpanTagKey.AUTH_ERROR.getTagForValue(true));
                        }
                        return toFailedAuthenticationResult(dittoRuntimeException, propagatedHeaders);
                    } else {
                        timer.tag(SpanTagKey.AUTH_SUCCESS.getTagForValue(false));
                        timer.tag(SpanTagKey.AUTH_ERROR.getTagForValue(true));
                        return toFailedAuthenticationResult(rootCause, propagatedHeaders);
                    }
                });
        resultFuture.whenComplete((result, error) -> {
            timer.stop();
            startedSpan.finish();
        });

        return resultFuture;
    }

    private static Throwable getRootCause(final Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        } else {
            return error;
        }
    }

    private static boolean isInternalError(final HttpStatus httpStatusCode) {
        return httpStatusCode.isServerError();
    }

    /**
     * Used to identify the authentication provider in order to distinguish measured metrics for this authentication
     * provider.
     *
     * @param requestContext the request context to authenticate.
     * @return the type of this authentication provider.
     */
    protected abstract AuthorizationContextType getType(RequestContext requestContext);

    /**
     * Authenticates the given {@link RequestContext request context}.
     *
     * @param requestContext the request context to authenticate.
     * @param dittoHeaders the (potentially not completely set) DittoHeaders of the request.
     * @return the authentication result.
     */
    protected abstract CompletableFuture<R> tryToAuthenticate(RequestContext requestContext, DittoHeaders dittoHeaders);

    /**
     * Creates failed authentication result with a {@link AuthenticationResult#getReasonOfFailure() reason of failure}
     * based on the given throwable.
     *
     * @param throwable the throwable that caused a failure.
     * @param dittoHeaders the (potentially not completely set) DittoHeaders of the request.
     * @return a failed authentication result holding the extracted reason of failure.
     */
    protected abstract R toFailedAuthenticationResult(Throwable throwable, DittoHeaders dittoHeaders);

    /**
     * Converts the given {@link Throwable} to a {@link DittoRuntimeException} either by returning the
     * ditto runtime exception hold as a cause or by building a
     * {@link GatewayAuthenticationProviderUnavailableException} with the given throwable as cause (Unwrapped in case
     * the throwable is of type {@link java.util.concurrent.CompletionException}).
     *
     * @param throwable the throwable to convert to a {@link org.eclipse.ditto.base.model.exceptions.DittoRuntimeException}.
     * @param dittoHeaders the built DittoHeaders of the request that caused the given throwable.
     * @return the converted exception.
     */
    protected DittoRuntimeException toDittoRuntimeException(final Throwable throwable,
            final DittoHeaders dittoHeaders) {

        return DittoRuntimeException.asDittoRuntimeException(throwable,
                cause -> {
                    final DittoRuntimeException dittoRuntimeException =
                            unwrapDittoRuntimeException(cause, dittoHeaders);

                    if (dittoRuntimeException == null) {
                        logger.withCorrelationId(dittoHeaders)
                                .warn("Failed to unwrap DittoRuntimeException from Throwable!", throwable);
                        return buildInternalErrorException(cause, dittoHeaders);
                    }

                    return dittoRuntimeException;
                });
    }

    private static DittoRuntimeException unwrapDittoRuntimeException(final Throwable throwable,
            final DittoHeaders dittoHeaders) {

        if (null == throwable) {
            return null;
        }

        if (throwable instanceof DittoRuntimeException dittoRuntimeException) {
            if (dittoRuntimeException.getDittoHeaders().getCorrelationId().isPresent()) {
                return dittoRuntimeException;
            }
            return dittoRuntimeException.setDittoHeaders(dittoHeaders);
        }

        return unwrapDittoRuntimeException(throwable.getCause(), dittoHeaders);
    }

    protected CompletableFuture<R> failOnTimeout(
            final CompletionStage<R> authenticationResultFuture, final DittoHeaders dittoHeaders) {
        return AuthenticationResultOrTimeout.of(authenticationResultFuture.toCompletableFuture(), dittoHeaders).get()
                .exceptionally(e -> toFailedAuthenticationResult(e, dittoHeaders));
    }

    protected static DittoRuntimeException buildInternalErrorException(final Throwable cause,
            final DittoHeaders dittoHeaders) {

        return GatewayAuthenticationProviderUnavailableException.newBuilder()
                .dittoHeaders(dittoHeaders)
                .cause(cause)
                .build();
    }

}
