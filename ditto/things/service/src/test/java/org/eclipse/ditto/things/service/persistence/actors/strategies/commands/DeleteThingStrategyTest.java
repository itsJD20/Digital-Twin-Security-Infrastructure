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

import static org.eclipse.ditto.things.model.TestConstants.Thing.THING_V2;

import org.apache.pekko.actor.ActorSystem;
import org.eclipse.ditto.internal.utils.persistentactors.commands.CommandStrategy;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.signals.commands.modify.DeleteThing;
import org.eclipse.ditto.things.model.signals.commands.modify.DeleteThingResponse;
import org.eclipse.ditto.things.model.signals.events.ThingDeleted;
import org.junit.Before;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Unit test for {@link DeleteThingStrategy}.
 */
public final class DeleteThingStrategyTest extends AbstractCommandStrategyTest {

    private DeleteThingStrategy underTest;

    @Before
    public void setUp() {
        final ActorSystem system = ActorSystem.create("test", ConfigFactory.load("test"));
        underTest = new DeleteThingStrategy(system);
    }

    @Test
    public void successfullyDeleteThing() {
        final CommandStrategy.Context<ThingId> context = getDefaultContext();
        final DeleteThing command = DeleteThing.of(context.getState(), provideHeaders(context));

        assertModificationResult(underTest, THING_V2, command,
                ThingDeleted.class,
                DeleteThingResponse.of(context.getState(), command.getDittoHeaders()), true);
    }

}
