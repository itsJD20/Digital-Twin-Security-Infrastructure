/*
 * Copyright (c) 2024 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.internal.utils.metrics.service.mongo;

import java.util.concurrent.atomic.LongAccumulator;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.internal.utils.metrics.DittoMetrics;
import org.eclipse.ditto.internal.utils.metrics.instruments.timer.StartedTimer;

import pekko.contrib.persistence.mongodb.MetricsBuilder;
import pekko.contrib.persistence.mongodb.MongoHistogram;
import pekko.contrib.persistence.mongodb.MongoTimer;

/**
 * An pekko-persistence-mongodb {@link MetricsBuilder} which uses {@link DittoMetrics} in order to provide timers and
 * histograms.
 */
@Immutable
public final class MongoMetricsBuilder implements MetricsBuilder {

    private static final LongAccumulator MAX_TIMER_NANOS = createMaxTimerNanos();

    @Override
    public MongoTimer timer(final String name) {
        return () -> {
            final StartedTimer startedTimer = DittoMetrics.timer(name).start();
            return () -> {
                final long nanos = startedTimer.stop().getDuration().toNanos();
                MAX_TIMER_NANOS.accumulate(nanos);
                return nanos;
            };
        };
    }

    @Override
    public MongoHistogram histogram(final String name) {
        return value -> DittoMetrics.histogram(name).record((long) value);
    }

    /**
     * Retrieve the accumulator for maximum Mongo journal interaction time.
     *
     * @return maximum Mongo journal interaction time.
     */
    public static LongAccumulator maxTimerNanos() {
        return MAX_TIMER_NANOS;
    }

    private static LongAccumulator createMaxTimerNanos() {
        return new LongAccumulator(Math::max, 0L);
    }
}
