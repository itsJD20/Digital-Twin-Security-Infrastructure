/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
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

import static org.eclipse.ditto.things.model.TestConstants.Thing.DEFINITION;
import static org.eclipse.ditto.things.model.TestConstants.Thing.THING_V2;

import org.apache.pekko.actor.ActorSystem;
import org.eclipse.ditto.internal.utils.persistentactors.commands.CommandStrategy;
import org.eclipse.ditto.things.model.ThingId;
import org.eclipse.ditto.things.model.signals.commands.modify.ModifyThingDefinition;
import org.eclipse.ditto.things.model.signals.events.ThingDefinitionCreated;
import org.eclipse.ditto.things.model.signals.events.ThingDefinitionModified;
import org.eclipse.ditto.things.service.persistence.actors.ETagTestUtils;
import org.junit.Before;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Unit test for {@link ModifyThingDefinitionStrategy}.
 */
public final class ModifyThingDefinitionStrategyTest extends AbstractCommandStrategyTest {

    private ModifyThingDefinitionStrategy underTest;

    @Before
    public void setUp() {
        final ActorSystem system = ActorSystem.create("test", ConfigFactory.load("test"));
        underTest = new ModifyThingDefinitionStrategy(system);
    }

    @Test
    public void modifyDefinitionOnThingWithoutDefinition() {
        final CommandStrategy.Context<ThingId> context = getDefaultContext();
        final ModifyThingDefinition command = ModifyThingDefinition.of(context.getState(), DEFINITION,
                provideHeaders(context));

        assertStagedModificationResult(underTest, THING_V2.toBuilder().setDefinition(null).build(), command,
                ThingDefinitionCreated.class, ETagTestUtils.modifyThingDefinitionResponse(context.getState(), command.getDefinition(),
                        command.getDittoHeaders(), true));
    }

    @Test
    public void modifyExistingDefinition() {
        final CommandStrategy.Context<ThingId> context = getDefaultContext();
        final ModifyThingDefinition command = ModifyThingDefinition.of(context.getState(), DEFINITION,
                provideHeaders(context));

        assertStagedModificationResult(underTest, THING_V2, command,
                ThingDefinitionModified.class, ETagTestUtils.modifyThingDefinitionResponse(context.getState(),
                        command.getDefinition(),
                        command.getDittoHeaders(), false));
    }

}
