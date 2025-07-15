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
import org.eclipse.ditto.things.model.Feature;
import org.eclipse.ditto.things.model.Thing;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.signals.commands.modify.DeleteFeatureProperties;
import org.eclipse.ditto.things.model.signals.commands.modify.DeleteFeaturePropertiesResponse;
import org.eclipse.ditto.things.model.signals.events.FeaturePropertiesDeleted;
import org.eclipse.ditto.things.model.signals.events.ThingEvent;

/**
 * This strategy handles the {@link org.eclipse.ditto.things.model.signals.commands.modify.DeleteFeatureProperties} command.
 */
@Immutable
final class DeleteFeaturePropertiesStrategy extends AbstractThingModifyCommandStrategy<DeleteFeatureProperties> {

    /**
     * Constructs a new {@code DeleteFeaturePropertiesStrategy} object.
     *
     * @param actorSystem the actor system to use for loading the WoT extension.
     */
    DeleteFeaturePropertiesStrategy(final ActorSystem actorSystem) {
        super(DeleteFeatureProperties.class, actorSystem);
    }

    @Override
    protected Result<ThingEvent<?>> doApply(final Context<ThingId> context,
            @Nullable final Thing thing,
            final long nextRevision,
            final DeleteFeatureProperties command,
            @Nullable final Metadata metadata) {

        return extractFeature(command, thing)
                .map(feature -> getDeleteFeaturePropertiesResult(feature, context, nextRevision, command, thing,
                        metadata))
                .orElseGet(() -> ResultFactory.newErrorResult(
                        ExceptionFactory.featureNotFound(context.getState(), command.getFeatureId(),
                                command.getDittoHeaders()), command));
    }

    @Override
    protected CompletionStage<DeleteFeatureProperties> performWotValidation(
            final DeleteFeatureProperties command,
            @Nullable final Thing previousThing,
            @Nullable final Thing previewThing
    ) {
        return wotThingModelValidator.validateFeatureScopedDeletion(
                Optional.ofNullable(previousThing)
                        .flatMap(Thing::getDefinition)
                        .orElse(null),
                Optional.ofNullable(previousThing)
                        .flatMap(Thing::getFeatures)
                        .flatMap(f -> f.getFeature(command.getFeatureId()))
                        .flatMap(Feature::getDefinition)
                        .orElse(null),
                command.getFeatureId(),
                command.getResourcePath(),
                command.getDittoHeaders()
        ).thenApply(aVoid -> command);
    }

    private Optional<Feature> extractFeature(final DeleteFeatureProperties command,
            final @Nullable Thing thing) {

        return getEntityOrThrow(thing).getFeatures()
                .flatMap(features -> features.getFeature(command.getFeatureId()));
    }

    private Result<ThingEvent<?>> getDeleteFeaturePropertiesResult(final Feature feature,
            final Context<ThingId> context,
            final long nextRevision, final DeleteFeatureProperties command, @Nullable final Thing thing,
            @Nullable final Metadata metadata) {
        final DittoHeaders dittoHeaders = command.getDittoHeaders();

        final ThingId thingId = context.getState();
        final String featureId = feature.getId();

        return feature.getProperties()
                .map(featureProperties -> {
                    final CompletionStage<DeleteFeatureProperties> validatedStage = buildValidatedStage(command, thing);
                    final CompletionStage<ThingEvent<?>> eventStage =
                            validatedStage.thenApply(deleteFeatureProperties ->
                                    FeaturePropertiesDeleted.of(thingId, featureId, nextRevision, getEventTimestamp(),
                                            dittoHeaders, metadata)
                            );
                    final CompletionStage<WithDittoHeaders> responseStage = validatedStage
                            .thenApply(deleteFeatureDesiredProperties ->
                                    appendETagHeaderIfProvided(deleteFeatureDesiredProperties,
                                            DeleteFeaturePropertiesResponse.of(thingId, featureId,
                                                    createCommandResponseDittoHeaders(dittoHeaders, nextRevision)),
                                            thing)
                            );

                    return ResultFactory.newMutationResult(command, eventStage, responseStage);
                })
                .orElseGet(() -> ResultFactory.newErrorResult(
                        ExceptionFactory.featurePropertiesNotFound(thingId, featureId, dittoHeaders), command));
    }

    @Override
    public Optional<EntityTag> previousEntityTag(final DeleteFeatureProperties command,
            @Nullable final Thing previousEntity) {
        return extractFeature(command, previousEntity).flatMap(Feature::getProperties).flatMap(EntityTag::fromEntity);
    }

    @Override
    public Optional<EntityTag> nextEntityTag(final DeleteFeatureProperties command, @Nullable final Thing newEntity) {
        return Optional.empty();
    }

}
