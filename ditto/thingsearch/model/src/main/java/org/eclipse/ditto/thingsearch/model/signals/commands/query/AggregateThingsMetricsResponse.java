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
 *
 */

package org.eclipse.ditto.thingsearch.model.signals.commands.query;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.eclipse.ditto.base.model.common.HttpStatus;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.json.FieldType;
import org.eclipse.ditto.base.model.json.JsonParsableCommandResponse;
import org.eclipse.ditto.base.model.json.JsonSchemaVersion;
import org.eclipse.ditto.base.model.signals.commands.AbstractCommandResponse;
import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonField;
import org.eclipse.ditto.json.JsonFieldDefinition;
import org.eclipse.ditto.json.JsonMissingFieldException;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonObjectBuilder;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.json.JsonValue;

/**
 * A response to an {@link AggregateThingsMetrics} command.
 * Contains the original aggregation as returned by the database as well as fields initialized by that aggregation
 * (grouped by and result)
 * The result contains the returned values for each filter defined in a metric.
 * The groupedBy contains the values that the result was grouped by.
 */
@JsonParsableCommandResponse(type = AggregateThingsMetricsResponse.RESOURCE_TYPE)
public final class AggregateThingsMetricsResponse extends AbstractCommandResponse<AggregateThingsMetricsResponse> {

    public static final String NAME = "things-metrics";
    public static final String RESOURCE_TYPE = "aggregation." + TYPE_QUALIFIER;
    private static final String TYPE_PREFIX = RESOURCE_TYPE + ":";
    private static final String TYPE = TYPE_PREFIX + NAME;

    static final JsonFieldDefinition<String> METRIC_NAME =
            JsonFactory.newStringFieldDefinition("metric-name", FieldType.REGULAR,
                    JsonSchemaVersion.V_2);
    static final JsonFieldDefinition<JsonObject> DITTO_HEADERS =
            JsonFactory.newJsonObjectFieldDefinition("ditto-headers", FieldType.REGULAR,
                    JsonSchemaVersion.V_2);
    static final JsonFieldDefinition<String> FILTER =
            JsonFactory.newStringFieldDefinition("filter", FieldType.REGULAR,
                    JsonSchemaVersion.V_2);
    static final JsonFieldDefinition<JsonObject> AGGREGATION =
            JsonFactory.newJsonObjectFieldDefinition("aggregation", FieldType.REGULAR,
                    JsonSchemaVersion.V_2);

    private final String metricName;
    private final DittoHeaders dittoHeaders;
    private final JsonObject aggregation;

    private AggregateThingsMetricsResponse(final String metricName, final DittoHeaders dittoHeaders,
            final JsonObject aggregation) {
        super(TYPE, HttpStatus.OK, dittoHeaders);
        this.metricName = metricName;
        this.dittoHeaders = DittoHeaders.of(dittoHeaders);
        this.aggregation = aggregation;
    }

    /**
     * Creates a new {@link AggregateThingsMetricsResponse} instance.
     *
     * @param aggregation the aggregation result.
     * @param aggregateThingsMetrics the command that was executed.
     * @return the AggregateThingsMetricsResponse instance.
     */
    public static AggregateThingsMetricsResponse of(final JsonObject aggregation,
            final AggregateThingsMetrics aggregateThingsMetrics) {
        return of(aggregation, aggregateThingsMetrics.getDittoHeaders(), aggregateThingsMetrics.getMetricName());
    }

    /**
     * Creates a new {@link AggregateThingsMetricsResponse} instance.
     *
     * @param aggregation the aggregation result.
     * @param dittoHeaders the headers to use for the response.
     * @param metricName the name of the metric.
     * @return the AggregateThingsMetricsResponse instance.
     */
    public static AggregateThingsMetricsResponse of(final JsonObject aggregation, final DittoHeaders dittoHeaders,
            final String metricName) {
        return new AggregateThingsMetricsResponse(metricName, dittoHeaders, aggregation);
    }

    /**
     * Creates a new {@code AggregateThingsMetricsResponse} from a JSON string.
     *
     * @param jsonString the JSON string of which the command is to be created.
     * @param dittoHeaders the headers of the command.
     * @return the command.
     * @throws NullPointerException if {@code jsonString} is {@code null}.
     * @throws IllegalArgumentException if {@code jsonString} is empty.
     * @throws org.eclipse.ditto.json.JsonParseException if the passed in {@code jsonString} was not in the expected
     * format.
     */
    public static AggregateThingsMetricsResponse fromJson(final String jsonString, final DittoHeaders dittoHeaders) {
        return fromJson(JsonFactory.newObject(jsonString), dittoHeaders);
    }

    /**
     * Creates a new {@code AggregateThingsMetricsResponse} from a JSON object.
     *
     * @param jsonObject the JSON object of which the command is to be created.
     * @param dittoHeaders the headers of the command.
     * @return the command.
     * @throws NullPointerException if {@code jsonObject} is {@code null}.
     * @throws org.eclipse.ditto.json.JsonParseException if the passed in {@code jsonObject} was not in the expected
     * format.
     */
    public static AggregateThingsMetricsResponse fromJson(final JsonObject jsonObject,
            final DittoHeaders dittoHeaders) {

        final JsonObject aggregation = jsonObject.getValue(AGGREGATION)
                .orElseThrow(getJsonMissingFieldExceptionSupplier(AGGREGATION.getPointer().toString()));
        final String metricName = jsonObject.getValue(METRIC_NAME)
                .orElseThrow(getJsonMissingFieldExceptionSupplier(METRIC_NAME.getPointer().toString()));
        return AggregateThingsMetricsResponse.of(aggregation, dittoHeaders, metricName);
    }

    @Override
    public AggregateThingsMetricsResponse setDittoHeaders(final DittoHeaders dittoHeaders) {
        return AggregateThingsMetricsResponse.of(aggregation, dittoHeaders, metricName);
    }

    @Override
    public JsonPointer getResourcePath() {
        return JsonPointer.empty();
    }

    @Override
    public String getResourceType() {
        return RESOURCE_TYPE;
    }


    @Override
    protected void appendPayload(final JsonObjectBuilder jsonObjectBuilder, final JsonSchemaVersion schemaVersion,
            final Predicate<JsonField> thePredicate) {

        final Predicate<JsonField> predicate = schemaVersion.and(thePredicate);
        jsonObjectBuilder.set(METRIC_NAME, metricName, predicate);
        jsonObjectBuilder.set(DITTO_HEADERS, dittoHeaders.toJson(), predicate);
        jsonObjectBuilder.set(AGGREGATION, aggregation, predicate);
    }

    /**
     * Returns the grouping by values by witch the result was grouped.
     *
     * @return the groupedBy of the response.
     */
    public Map<String, String> getGroupedBy() {
        return aggregation.getValue("_id")
                .map(json -> {
                            if (json.isObject()) {
                                return json.asObject().stream()
                                        .collect(Collectors.toMap(o -> o.getKey().toString(),
                                                o1 -> o1.getValue().formatAsString()));
                            } else {
                                return new HashMap<String, String>();
                            }
                        }
                )
                .orElse(new HashMap<>());
    }

    /**
     * Returns the values for the single matched filter defined in the metric.
     *
     * @return the result of the aggregation, a single filter name with its count or an empty optional if no filter
     * provided a count greater 0.
     */
    public Optional<Long> getResult() {
        return aggregation.getValue(JsonPointer.of("count")).map(JsonValue::asLong);
    }

    /**
     * Returns the metric name.
     *
     * @return the metric name.
     */
    public String getMetricName() {
        return metricName;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final AggregateThingsMetricsResponse response = (AggregateThingsMetricsResponse) o;
        return Objects.equals(metricName, response.metricName) &&
                Objects.equals(dittoHeaders, response.dittoHeaders) &&
                Objects.equals(aggregation, response.aggregation);

    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), metricName, dittoHeaders, aggregation);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" +
                "metricName='" + metricName + '\'' +
                ", dittoHeaders=" + dittoHeaders +
                ", aggregation=" + aggregation +
                "]";
    }

    private static Supplier<RuntimeException> getJsonMissingFieldExceptionSupplier(final String field) {
        return () -> JsonMissingFieldException.newBuilder().fieldName(field).build();
    }
}
