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
package org.eclipse.ditto.wot.api.generator;

import static org.eclipse.ditto.base.model.common.ConditionChecker.checkNotNull;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.base.model.exceptions.DittoRuntimeException;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.signals.FeatureToggle;
import org.eclipse.ditto.json.JsonArray;
import org.eclipse.ditto.json.JsonArrayBuilder;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonObjectBuilder;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.things.model.Attributes;
import org.eclipse.ditto.things.model.AttributesBuilder;
import org.eclipse.ditto.things.model.DefinitionIdentifier;
import org.eclipse.ditto.things.model.Feature;
import org.eclipse.ditto.things.model.FeatureBuilder;
import org.eclipse.ditto.things.model.FeatureDefinition;
import org.eclipse.ditto.things.model.FeatureProperties;
import org.eclipse.ditto.things.model.FeaturePropertiesBuilder;
import org.eclipse.ditto.things.model.Features;
import org.eclipse.ditto.things.model.FeaturesBuilder;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingBuilder;
import org.eclipse.ditto.things.model.ThingDefinition;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.ThingsModelFactory;
import org.eclipse.ditto.wot.api.config.WotConfig;
import org.eclipse.ditto.wot.api.resolver.WotThingModelResolver;
import org.eclipse.ditto.wot.model.ArraySchema;
import org.eclipse.ditto.wot.model.BaseLink;
import org.eclipse.ditto.wot.model.DataSchemaType;
import org.eclipse.ditto.wot.model.DittoWotExtension;
import org.eclipse.ditto.wot.model.IRI;
import org.eclipse.ditto.wot.model.ObjectSchema;
import org.eclipse.ditto.wot.model.Properties;
import org.eclipse.ditto.wot.model.Property;
import org.eclipse.ditto.wot.model.SingleDataSchema;
import org.eclipse.ditto.wot.model.ThingDefinitionInvalidException;
import org.eclipse.ditto.wot.model.ThingModel;
import org.eclipse.ditto.wot.model.TmOptional;
import org.eclipse.ditto.wot.model.WotInternalErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default Ditto specific implementation of {@link WotThingSkeletonGenerator}.
 */
@Immutable
final class DefaultWotThingSkeletonGenerator implements WotThingSkeletonGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultWotThingSkeletonGenerator.class);

    private static final String TM_EXTENDS = "tm:extends";

    private static final int NEUTRAL_ELEMENT_INT = 0;
    private static final double NEUTRAL_ELEMENT_DOUBLE = 0.0;
    private static final String NEUTRAL_ELEMENT_STRING = "";

    private final WotConfig wotConfig;
    private final WotThingModelResolver thingModelResolver;
    private final Executor executor;

    DefaultWotThingSkeletonGenerator(final WotConfig wotConfig,
            final WotThingModelResolver thingModelResolver,
            final Executor executor) {
        this.thingModelResolver = checkNotNull(thingModelResolver, "thingModelResolver");
        this.wotConfig = checkNotNull(wotConfig, "wotConfig");
        this.executor = executor;
    }

    @Override
    public CompletionStage<Optional<Thing>> provideThingSkeletonForCreation(final ThingId thingId,
            @Nullable final ThingDefinition thingDefinition,
            final DittoHeaders dittoHeaders) {

        if (FeatureToggle.isWotIntegrationFeatureEnabled() &&
                wotConfig.getCreationConfig().getThingCreationConfig().isSkeletonCreationEnabled() &&
                null != thingDefinition) {
            final Optional<URL> urlOpt = thingDefinition.getUrl();
            if (urlOpt.isPresent()) {
                final URL url = urlOpt.get();
                LOGGER.debug("Resolving ThingModel from <{}> in order to create Thing skeleton for new Thing " +
                        "with id <{}>", url, thingId);
                return thingModelResolver.resolveThingModel(url, dittoHeaders)
                        .thenComposeAsync(thingModel -> generateThingSkeleton(
                                        thingId,
                                        thingModel,
                                        url,
                                        wotConfig.getCreationConfig()
                                                .getThingCreationConfig()
                                                .shouldGenerateDefaultsForOptionalProperties(),
                                        dittoHeaders
                                ),
                                executor
                        )
                        .handleAsync((thingSkeleton, throwable) -> {
                            if (throwable != null) {
                                LOGGER.info("Could not fetch ThingModel or generate Thing skeleton based on it due " +
                                                "to: <{}: {}>",
                                        throwable.getClass().getSimpleName(), throwable.getMessage(), throwable);
                                if (wotConfig.getCreationConfig()
                                        .getThingCreationConfig()
                                        .shouldThrowExceptionOnWotErrors()) {
                                    throw DittoRuntimeException.asDittoRuntimeException(
                                            throwable, t -> WotInternalErrorException.newBuilder()
                                                    .dittoHeaders(dittoHeaders)
                                                    .cause(t)
                                                    .build()
                                    );
                                } else {
                                    return Optional.empty();
                                }
                            } else {
                                LOGGER.debug("Created Thing skeleton for new Thing with id <{}>: <{}>", thingId,
                                        thingSkeleton);
                                return thingSkeleton;
                            }
                        }, executor);
            } else {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        } else {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    @Override
    public CompletionStage<Optional<Feature>> provideFeatureSkeletonForCreation(final String featureId,
            @Nullable final FeatureDefinition featureDefinition, final DittoHeaders dittoHeaders) {

        if (FeatureToggle.isWotIntegrationFeatureEnabled() &&
                wotConfig.getCreationConfig().getFeatureCreationConfig().isSkeletonCreationEnabled() &&
                null != featureDefinition) {
            final Optional<URL> urlOpt = featureDefinition.getFirstIdentifier().getUrl();
            if (urlOpt.isPresent()) {
                final URL url = urlOpt.get();
                LOGGER.debug("Resolving ThingModel from <{}> in order to create Feature skeleton for new Feature " +
                        "with id <{}>", url, featureId);
                return thingModelResolver.resolveThingModel(url, dittoHeaders)
                        .thenComposeAsync(thingModel -> generateFeatureSkeleton(
                                        featureId,
                                        thingModel,
                                        url,
                                        wotConfig.getCreationConfig()
                                                .getFeatureCreationConfig()
                                                .shouldGenerateDefaultsForOptionalProperties(),
                                        dittoHeaders
                                ),
                                executor
                        )
                        .handleAsync((featureSkeleton, throwable) -> {
                            if (throwable != null) {
                                LOGGER.info("Could not fetch ThingModel or generate Feature skeleton based on it due " +
                                                "to: <{}: {}>",
                                        throwable.getClass().getSimpleName(), throwable.getMessage(), throwable);
                                if (wotConfig.getCreationConfig()
                                        .getFeatureCreationConfig()
                                        .shouldThrowExceptionOnWotErrors()) {
                                    throw DittoRuntimeException.asDittoRuntimeException(
                                            throwable, t -> WotInternalErrorException.newBuilder()
                                                    .dittoHeaders(dittoHeaders)
                                                    .cause(t)
                                                    .build()
                                    );
                                } else {
                                    return Optional.empty();
                                }
                            } else {
                                LOGGER.debug("Created Feature skeleton for new Feature with id <{}>: <{}>", featureId,
                                        featureSkeleton);
                                return featureSkeleton;
                            }
                        }, executor);
            } else {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        } else {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    @Override
    public CompletionStage<Optional<Thing>> generateThingSkeleton(final ThingId thingId,
            final ThingModel thingModel,
            final URL thingModelUrl,
            final boolean generateDefaultsForOptionalProperties,
            final DittoHeaders dittoHeaders) {

        return CompletableFuture.completedFuture(thingModel)
                .thenApplyAsync(thingModelWithExtensionsAndImports -> {
                    final Optional<String> dittoExtensionPrefix = thingModelWithExtensionsAndImports.getAtContext()
                            .determinePrefixFor(DittoWotExtension.DITTO_WOT_EXTENSION);

                    LOGGER.debug("ThingModel for generating Thing skeleton after resolving extensions + refs: <{}>",
                            thingModelWithExtensionsAndImports);

                    final ThingBuilder.FromScratch builder = Thing.newBuilder();
                    thingModelWithExtensionsAndImports.getProperties()
                            .map(properties -> {
                                final JsonObjectBuilder jsonObjectBuilder = JsonObject.newBuilder();
                                final Map<String, JsonObjectBuilder> attributesCategories = new LinkedHashMap<>();

                                fillPropertiesInOptionalCategories(
                                        properties,
                                        generateDefaultsForOptionalProperties,
                                        thingModelWithExtensionsAndImports.getTmOptional().orElse(null),
                                        jsonObjectBuilder,
                                        attributesCategories,
                                        property -> dittoExtensionPrefix.flatMap(prefix ->
                                                        property.getValue(prefix + ":" +
                                                                DittoWotExtension.DITTO_WOT_EXTENSION_CATEGORY
                                                        )
                                                )
                                                .filter(JsonValue::isString)
                                                .map(JsonValue::asString)
                                );

                                final AttributesBuilder attributesBuilder = Attributes.newBuilder();
                                if (!attributesCategories.isEmpty()) {
                                    attributesCategories.forEach((attributeCategory, categoryObjBuilder) ->
                                            attributesBuilder.set(attributeCategory, categoryObjBuilder.build())
                                    );
                                }
                                attributesBuilder.setAll(jsonObjectBuilder.build());
                                return attributesBuilder.build();
                            }).ifPresent(builder::setAttributes);

                    return new AbstractMap.SimpleImmutableEntry<>(thingModelWithExtensionsAndImports, builder);
                }, executor)
                .thenComposeAsync(pair ->
                        createFeaturesFromSubmodels(pair.getKey(), generateDefaultsForOptionalProperties, dittoHeaders)
                                .thenApplyAsync(features ->
                                        features.map(f -> pair.getValue().setFeatures(f)).orElse(pair.getValue()),
                                        executor
                                ),
                        executor
                )
                .thenApplyAsync(builder -> Optional.of(builder.build()), executor);
    }

    private static void fillPropertiesInOptionalCategories(final Properties properties,
            final boolean generateDefaultsForOptionalProperties,
            @Nullable final TmOptional tmOptionalElements,
            final JsonObjectBuilder jsonObjectBuilder,
            final Map<String, JsonObjectBuilder> propertiesCategories,
            final Function<Property, Optional<String>> propertyCategoryExtractor) {

        properties.values().stream()
                .filter(property -> generateDefaultsForOptionalProperties || Optional.ofNullable(tmOptionalElements)
                        .stream()
                        .noneMatch(optionals -> optionals.stream()
                                .anyMatch(optionalEl ->
                                        // filter out optional elements - don't create skeleton values for those:
                                        optionalEl.toString().equals("/properties/" + property.getPropertyName())
                                )
                        )
                )
                .forEach(property -> determineInitialPropertyValue(property).ifPresent(val ->
                                propertyCategoryExtractor.apply(property)
                                        .ifPresentOrElse(attributeCategory -> {
                                                    if (!propertiesCategories.containsKey(attributeCategory)) {
                                                        propertiesCategories.put(attributeCategory,
                                                                JsonObject.newBuilder());
                                                    }
                                                    propertiesCategories.get(attributeCategory)
                                                            .set(property.getPropertyName(), val);
                                                }, () ->
                                                        jsonObjectBuilder.set(property.getPropertyName(), val)
                                        )
                        )
                );
    }

    private CompletionStage<Optional<Features>> createFeaturesFromSubmodels(final ThingModel thingModel,
            final boolean generateDefaultsForOptionalProperties, final DittoHeaders dittoHeaders) {

        final CompletionStage<List<CompletableFuture<Optional<Feature>>>> futureListStage =
                thingModelResolver.resolveThingModelSubmodels(thingModel, dittoHeaders)
                        .thenApplyAsync(submodelMap -> submodelMap.entrySet().stream()
                                        .map(entry -> generateFeatureSkeleton(entry.getKey().instanceName(),
                                                entry.getValue(),
                                                entry.getKey().href(),
                                                generateDefaultsForOptionalProperties,
                                                dittoHeaders
                                        ).toCompletableFuture())
                                        .toList()
                                , executor);

        final FeaturesBuilder featuresBuilder = Features.newBuilder();
        return futureListStage.thenComposeAsync(futureList ->
                CompletableFuture.allOf(futureList.toArray(CompletableFuture<?>[]::new))
                        .thenApplyAsync(v -> {
                                    if (futureList.isEmpty()) {
                                        return Optional.empty();
                                    } else {
                                        featuresBuilder.setAll(futureList.stream()
                                                .map(CompletableFuture::join)
                                                .filter(Optional::isPresent)
                                                .map(Optional::get)
                                                .toList());
                                        return Optional.of(featuresBuilder.build());
                                    }
                                },
                                executor
                        ),
                executor
        );
    }

    private CompletionStage<Optional<Feature>> generateFeatureSkeleton(final String featureId,
            final ThingModel thingModel,
            final IRI thingModelIri,
            final boolean generateDefaultsForOptionalProperties,
            final DittoHeaders dittoHeaders) {
        try {
            return generateFeatureSkeleton(featureId, thingModel, new URL(thingModelIri.toString()),
                    generateDefaultsForOptionalProperties, dittoHeaders);
        } catch (final MalformedURLException e) {
            throw ThingDefinitionInvalidException.newBuilder(thingModelIri)
                    .dittoHeaders(dittoHeaders)
                    .build();
        }
    }

    @Override
    public CompletionStage<Optional<Feature>> generateFeatureSkeleton(final String featureId,
            final ThingModel thingModel,
            final URL thingModelUrl,
            final boolean generateDefaultsForOptionalProperties,
            final DittoHeaders dittoHeaders) {

        return CompletableFuture.completedFuture(thingModel)
                .thenCombine(resolveFeatureDefinition(thingModel, thingModelUrl, dittoHeaders),
                        (thingModelWithExtensionsAndImports, featureDefinition) -> {
                            final Optional<String> dittoExtensionPrefix =
                                    thingModelWithExtensionsAndImports.getAtContext()
                                            .determinePrefixFor(DittoWotExtension.DITTO_WOT_EXTENSION);

                            LOGGER.debug(
                                    "ThingModel for generating Feature skeleton after resolving extensions + refs: <{}>",
                                    thingModelWithExtensionsAndImports);

                            final FeatureBuilder.FromScratchBuildable builder = Feature.newBuilder();
                            thingModelWithExtensionsAndImports.getProperties()
                                    .map(properties -> {
                                        final JsonObjectBuilder jsonObjectBuilder = JsonObject.newBuilder();
                                        final Map<String, JsonObjectBuilder> propertiesCategories =
                                                new LinkedHashMap<>();

                                        fillPropertiesInOptionalCategories(
                                                properties,
                                                generateDefaultsForOptionalProperties,
                                                thingModelWithExtensionsAndImports.getTmOptional().orElse(null),
                                                jsonObjectBuilder,
                                                propertiesCategories,
                                                property -> dittoExtensionPrefix.flatMap(prefix ->
                                                                property.getValue(
                                                                        prefix + ":" +
                                                                                DittoWotExtension.DITTO_WOT_EXTENSION_CATEGORY)
                                                        )
                                                        .filter(JsonValue::isString)
                                                        .map(JsonValue::asString)
                                        );

                                        final FeaturePropertiesBuilder propertiesBuilder =
                                                FeatureProperties.newBuilder();
                                        if (!propertiesCategories.isEmpty()) {
                                            propertiesCategories.forEach((propertyCategory, categoryObjBuilder) ->
                                                    propertiesBuilder.set(propertyCategory, categoryObjBuilder.build())
                                            );
                                        }
                                        propertiesBuilder.setAll(jsonObjectBuilder.build());
                                        return propertiesBuilder.build();
                                    }).ifPresent(builder::properties);

                            builder.definition(featureDefinition);

                            return Optional.of(builder.withId(featureId).build());
                        });
    }

    private static Optional<JsonValue> determineInitialPropertyValue(final SingleDataSchema dataSchema) {

        return dataSchema.getConst()
                .or(dataSchema::getDefault)
                .or(() -> {
                    final SingleDataSchema actualSchema;
                    if (dataSchema instanceof Property property) {
                        actualSchema = resolveActualPropertySchema(property);
                    } else {
                        actualSchema = dataSchema;
                    }

                    if (actualSchema instanceof ObjectSchema objectSchema) {
                        final List<String> required = objectSchema.getRequired();
                        if (!required.isEmpty()) {
                            final JsonObjectBuilder objectBuilder = JsonObject.newBuilder();
                            objectSchema.getProperties().entrySet().stream()
                                    .filter(e -> required.contains(e.getKey()))
                                    .forEach(entry -> objectBuilder.set(entry.getKey(),
                                            determineInitialPropertyValue(entry.getValue())
                                                    .orElse(JsonValue.nullLiteral())));
                            return Optional.of(objectBuilder.build());
                        }
                        return Optional.of(JsonObject.empty());
                    } else if (actualSchema instanceof ArraySchema) {
                        final JsonArrayBuilder arrayBuilder = JsonArray.newBuilder();
                        return Optional.of(arrayBuilder.build());
                    } else {
                        return provideNeutralElementForDataSchema(actualSchema);
                    }
                });
    }

    private static SingleDataSchema resolveActualPropertySchema(final Property property) {
        final SingleDataSchema actualSchema;
        if (property.isBooleanSchema()) {
            actualSchema = property.asBooleanSchema();
        } else if (property.isIntegerSchema()) {
            actualSchema = property.asIntegerSchema();
        } else if (property.isNumberSchema()) {
            actualSchema = property.asNumberSchema();
        } else if (property.isStringSchema()) {
            actualSchema = property.asStringSchema();
        } else if (property.isObjectSchema()) {
            actualSchema = property.asObjectSchema();
        } else if (property.isArraySchema()) {
            actualSchema = property.asArraySchema();
        } else if (property.isNullSchema()) {
            actualSchema = property.asNullSchema();
        } else {
            actualSchema = property;
        }
        return actualSchema;
    }

    private static Optional<JsonValue> provideNeutralElementForDataSchema(final SingleDataSchema dataSchema) {
        final DataSchemaType type = dataSchema.getType().orElse(null);
        if (null == type) {
            return Optional.empty();
        } else {
            return switch (type) {
                case BOOLEAN -> Optional.of(JsonValue.of(false));
                case INTEGER -> Optional.of(JsonValue.of(provideNeutralIntElement()));
                case NUMBER -> Optional.of(JsonValue.of(provideNeutralDoubleElement()));
                case STRING -> {
                    final String format = dataSchema.getFormat().orElse(null);
                    yield Optional.of(JsonValue.of(provideNeutralStringElement(format)));
                }
                case OBJECT -> Optional.of(JsonObject.empty());
                case ARRAY -> Optional.of(JsonArray.empty());
                case NULL -> Optional.of(JsonValue.nullLiteral());
            };
        }
    }

    private static int provideNeutralIntElement() {
        return NEUTRAL_ELEMENT_INT;
    }

    private static double provideNeutralDoubleElement() {
        return NEUTRAL_ELEMENT_DOUBLE;
    }

    private static String provideNeutralStringElement(@Nullable final String format) {
        return switch (format) {
            case "date-time" -> DateTimeFormatter.ISO_INSTANT.format(Instant.EPOCH);
            case "date" -> DateTimeFormatter.ISO_LOCAL_DATE.withZone( ZoneId.of("UTC")).format(Instant.EPOCH);
            case "time" -> DateTimeFormatter.ISO_OFFSET_TIME.withZone( ZoneId.of("UTC")).format(Instant.EPOCH);
            case null, default -> NEUTRAL_ELEMENT_STRING;
        };
    }

    private CompletionStage<FeatureDefinition> resolveFeatureDefinition(final ThingModel thingModel,
            final URL thingModelUrl,
            final DittoHeaders dittoHeaders) {
        return determineFurtherFeatureDefinitionIdentifiers(thingModel, dittoHeaders)
                .thenApplyAsync(definitionIdentifiers -> FeatureDefinition.fromIdentifier(
                        thingModelUrl.toString(),
                        definitionIdentifiers.toArray(DefinitionIdentifier[]::new)
                ), executor);
    }

    private CompletionStage<List<DefinitionIdentifier>> determineFurtherFeatureDefinitionIdentifiers(
            final ThingModel thingModel,
            final DittoHeaders dittoHeaders) {
        return thingModel.getLinks().map(links -> {
            final Optional<BaseLink<?>> extendsLink = links.stream()
                    .filter(baseLink -> baseLink.getRel().filter(TM_EXTENDS::equals).isPresent())
                    .findFirst();

            if (extendsLink.isPresent()) {
                final BaseLink<?> link = extendsLink.get();
                return thingModelResolver.resolveThingModel(link.getHref(), dittoHeaders)
                        .thenComposeAsync(subThingModel ->
                                determineFurtherFeatureDefinitionIdentifiers( // recurse!
                                        subThingModel,
                                        dittoHeaders
                                ), executor
                        )
                        .thenApplyAsync(recursedSubmodels -> {
                            final List<DefinitionIdentifier> combinedIdentifiers = new ArrayList<>();
                            combinedIdentifiers.add(ThingsModelFactory.newFeatureDefinitionIdentifier(link.getHref()));
                            combinedIdentifiers.addAll(recursedSubmodels);
                            return combinedIdentifiers;
                        }, executor);
            } else {
                return CompletableFuture.completedFuture(Collections.<DefinitionIdentifier>emptyList());
            }
        }).orElseGet(() -> CompletableFuture.completedFuture(Collections.emptyList()));
    }

}
