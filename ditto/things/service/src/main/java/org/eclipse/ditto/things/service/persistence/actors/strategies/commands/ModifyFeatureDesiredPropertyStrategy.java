/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.things.service.persistence.actors.strategies.commands;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.apache.pekko.actor.ActorSystem;
import org.eclipse.ditto.base.model.entity.metadata.Metadata;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.base.model.headers.WithDittoHeaders;
import org.eclipse.ditto.base.model.headers.entitytag.EntityTag;
import org.eclipse.ditto.internal.utils.persistentactors.results.Result;
import org.eclipse.ditto.internal.utils.persistentactors.results.ResultFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.things.model.Feature;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.signals.commands.ThingCommandSizeValidator;
import org.eclipse.ditto.things.model.signals.commands.modify.ModifyFeatureDesiredProperty;
import org.eclipse.ditto.things.model.signals.commands.modify.ModifyFeatureDesiredPropertyResponse;
import org.eclipse.ditto.things.model.signals.events.FeatureDesiredPropertyCreated;
import org.eclipse.ditto.things.model.signals.events.FeatureDesiredPropertyModified;
import org.eclipse.ditto.things.model.signals.events.ThingEvent;

/**
 * This strategy handles the {@link org.eclipse.ditto.things.model.signals.commands.modify.ModifyFeatureDesiredProperty} command.
 */
@Immutable
final class ModifyFeatureDesiredPropertyStrategy
        extends AbstractThingModifyCommandStrategy<ModifyFeatureDesiredProperty> {

    /**
     * Constructs a new {@code ModifyFeatureDesiredPropertyStrategy} object.
     *
     * @param actorSystem the actor system to use for loading the WoT extension.
     */
    ModifyFeatureDesiredPropertyStrategy(final ActorSystem actorSystem) {
        super(ModifyFeatureDesiredProperty.class, actorSystem);
    }

    @Override
    protected Result<ThingEvent<?>> doApply(final Context<ThingId> context,
            @Nullable final Thing thing,
            final long nextRevision,
            final ModifyFeatureDesiredProperty command,
            @Nullable final Metadata metadata) {

        final String featureId = command.getFeatureId();
        final Thing nonNullThing = getEntityOrThrow(thing);

        final JsonObject thingWithoutFeatureDesiredPropertyJsonObject =
                nonNullThing.removeFeatureDesiredProperty(featureId, command.getDesiredPropertyPointer()).toJson();
        final JsonValue propertyValue = command.getDesiredPropertyValue();

        ThingCommandSizeValidator.getInstance().ensureValidSize(
                () -> {
                    final long lengthWithOutProperty =
                            thingWithoutFeatureDesiredPropertyJsonObject.getUpperBoundForStringSize();
                    final long propertyLength =
                            propertyValue.getUpperBoundForStringSize() + command.getDesiredPropertyPointer().length() +
                                    5L;
                    return lengthWithOutProperty + propertyLength;
                },
                () -> {
                    final long lengthWithOutProperty = thingWithoutFeatureDesiredPropertyJsonObject.toString().length();
                    final long propertyLength =
                            propertyValue.toString().length() + command.getDesiredPropertyPointer().length() + 5L;
                    return lengthWithOutProperty + propertyLength;
                },
                command::getDittoHeaders);

        return extractFeature(command, nonNullThing)
                .map(feature -> getModifyOrCreateResult(feature, context, nextRevision, command, thing, metadata))
                .orElseGet(() -> ResultFactory.newErrorResult(
                        ExceptionFactory.featureNotFound(context.getState(), featureId,
                                createCommandResponseDittoHeaders(command.getDittoHeaders(), nextRevision)), command));
    }

    @Override
    protected CompletionStage<ModifyFeatureDesiredProperty> performWotValidation(
            final ModifyFeatureDesiredProperty command,
            @Nullable final Thing previousThing,
            @Nullable final Thing previewThing
    ) {
        return wotThingModelValidator.validateFeatureProperty(
                Optional.ofNullable(previousThing).flatMap(Thing::getDefinition).orElse(null),
                Optional.ofNullable(previousThing)
                        .flatMap(Thing::getFeatures)
                        .flatMap(f -> f.getFeature(command.getFeatureId()))
                        .flatMap(Feature::getDefinition)
                        .orElse(null),
                command.getFeatureId(),
                command.getDesiredPropertyPointer(),
                command.getDesiredPropertyValue(),
                true,
                command.getResourcePath(),
                command.getDittoHeaders()
        ).thenApply(aVoid -> command);
    }

    private Optional<Feature> extractFeature(final ModifyFeatureDesiredProperty command, @Nullable final Thing thing) {
        return Optional.ofNullable(thing)
                .flatMap(Thing::getFeatures)
                .flatMap(features -> features.getFeature(command.getFeatureId()));
    }

    private Result<ThingEvent<?>> getModifyOrCreateResult(final Feature feature,
            final Context<ThingId> context,
            final long nextRevision,
            final ModifyFeatureDesiredProperty command,
            @Nullable final Thing thing,
            @Nullable final Metadata metadata) {

        return feature.getDesiredProperties()
                .filter(desiredProperties -> desiredProperties.contains(command.getDesiredPropertyPointer()))
                .map(featureProperties -> getModifyResult(context, nextRevision, command, thing, metadata))
                .orElseGet(() -> getCreateResult(context, nextRevision, command, thing, metadata));
    }

    private Result<ThingEvent<?>> getModifyResult(final Context<ThingId> context,
            final long nextRevision,
            final ModifyFeatureDesiredProperty command,
            @Nullable final Thing thing,
            @Nullable final Metadata metadata) {

        final String featureId = command.getFeatureId();
        final JsonPointer propertyPointer = command.getDesiredPropertyPointer();
        final DittoHeaders dittoHeaders = command.getDittoHeaders();

        final CompletionStage<ModifyFeatureDesiredProperty> validatedStage = buildValidatedStage(command, thing);
        final CompletionStage<ThingEvent<?>> eventStage = validatedStage.thenApply(modifyFeatureDesiredProperty ->
                FeatureDesiredPropertyModified.of(command.getEntityId(), featureId, propertyPointer,
                        command.getDesiredPropertyValue(), nextRevision, getEventTimestamp(), dittoHeaders, metadata)
        );
        final CompletionStage<WithDittoHeaders> responseStage = validatedStage.thenApply(modifyFeatureDesiredProperty ->
                appendETagHeaderIfProvided(modifyFeatureDesiredProperty,
                        ModifyFeatureDesiredPropertyResponse.modified(context.getState(), featureId, propertyPointer,
                                createCommandResponseDittoHeaders(dittoHeaders, nextRevision)),
                        thing)
        );

        return ResultFactory.newMutationResult(command, eventStage, responseStage);
    }

    private Result<ThingEvent<?>> getCreateResult(final Context<ThingId> context,
            final long nextRevision,
            final ModifyFeatureDesiredProperty command,
            @Nullable final Thing thing,
            @Nullable final Metadata metadata) {

        final String featureId = command.getFeatureId();
        final JsonPointer propertyPointer = command.getDesiredPropertyPointer();
        final JsonValue propertyValue = command.getDesiredPropertyValue();
        final DittoHeaders dittoHeaders = command.getDittoHeaders();

        final CompletionStage<ModifyFeatureDesiredProperty> validatedStage = buildValidatedStage(command, thing);
        final CompletionStage<ThingEvent<?>> eventStage = validatedStage.thenApply(modifyFeatureDesiredProperty ->
                FeatureDesiredPropertyCreated.of(command.getEntityId(), featureId, propertyPointer, propertyValue,
                        nextRevision, getEventTimestamp(), dittoHeaders, metadata)
        );
        final CompletionStage<WithDittoHeaders> responseStage = validatedStage.thenApply(modifyFeatureDesiredProperty ->
                appendETagHeaderIfProvided(modifyFeatureDesiredProperty,
                        ModifyFeatureDesiredPropertyResponse.created(context.getState(), featureId, propertyPointer,
                                propertyValue, createCommandResponseDittoHeaders(dittoHeaders, nextRevision)),
                        thing)
        );

        return ResultFactory.newMutationResult(command, eventStage, responseStage);
    }

    @Override
    public Optional<EntityTag> previousEntityTag(final ModifyFeatureDesiredProperty command,
            @Nullable final Thing previousEntity) {

        return extractFeature(command, previousEntity).flatMap(Feature::getDesiredProperties)
                .flatMap(props -> props.getValue(command.getDesiredPropertyPointer()).flatMap(EntityTag::fromEntity));
    }

    @Override
    public Optional<EntityTag> nextEntityTag(final ModifyFeatureDesiredProperty command,
            @Nullable final Thing newEntity) {
        return Optional.of(command.getDesiredPropertyValue()).flatMap(EntityTag::fromEntity);
    }
}
