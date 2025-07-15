/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.policies.enforcement;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import org.apache.pekko.actor.AbstractActor;
import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.Props;
import org.apache.pekko.cluster.ddata.ORSet;
import org.apache.pekko.cluster.ddata.Replicator;
import org.apache.pekko.cluster.pubsub.DistributedPubSub;
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator;
import org.apache.pekko.dispatch.MessageDispatcher;
import org.apache.pekko.japi.pf.ReceiveBuilder;
import org.apache.pekko.pattern.Patterns;
import org.eclipse.ditto.base.model.exceptions.DittoInternalErrorException;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.internal.utils.cache.config.CacheConfig;
import org.eclipse.ditto.internal.utils.cache.config.DefaultCacheConfig;
import org.eclipse.ditto.internal.utils.cache.entry.Entry;
import org.eclipse.ditto.internal.utils.cluster.DistPubSubAccess;
import org.eclipse.ditto.internal.utils.namespaces.BlockedNamespaces;
import org.eclipse.ditto.internal.utils.pekko.logging.DittoDiagnosticLoggingAdapter;
import org.eclipse.ditto.internal.utils.pekko.logging.DittoLoggerFactory;
import org.eclipse.ditto.policies.api.PolicyTag;
import org.eclipse.ditto.policies.model.PolicyId;
import org.slf4j.Logger;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;

/**
 * Transparent caching layer for {@link org.eclipse.ditto.policies.enforcement.PolicyEnforcerProvider}
 */
final class CachingPolicyEnforcerProvider extends AbstractPolicyEnforcerProvider implements Invalidatable {

    private static final Logger LOGGER = DittoLoggerFactory.getThreadSafeLogger(CachingPolicyEnforcerProvider.class);
    private static final Duration LOCAL_POLICY_RETRIEVAL_TIMEOUT = Duration.ofSeconds(60);

    private final ActorRef cachingPolicyEnforcerProviderActor;

    CachingPolicyEnforcerProvider(final ActorSystem actorSystem) {
        this(actorSystem, policyEnforcerCacheLoader(actorSystem), enforcementCacheDispatcher(actorSystem),
                DefaultCacheConfig.of(actorSystem.settings().config(),
                        PolicyEnforcerProvider.ENFORCER_CACHE_CONFIG_KEY));
    }

    private CachingPolicyEnforcerProvider(final ActorSystem actorSystem,
            final AsyncCacheLoader<PolicyId, Entry<PolicyEnforcer>> policyEnforcerCacheLoader,
            final MessageDispatcher cacheDispatcher,
            final CacheConfig cacheConfig) {

        this(actorSystem, new PolicyEnforcerCache(policyEnforcerCacheLoader, cacheDispatcher, cacheConfig),
                BlockedNamespaces.of(actorSystem),
                DistributedPubSub.get(actorSystem).mediator(),
                cacheDispatcher
        );
    }

    CachingPolicyEnforcerProvider(final ActorSystem actorSystem,
            final PolicyEnforcerCache policyEnforcerCache,
            final BlockedNamespaces blockedNamespaces,
            final ActorRef pubSubMediator,
            final MessageDispatcher cacheDispatcher) {

        this.cachingPolicyEnforcerProviderActor = actorSystem.actorOf(
                CachingPolicyEnforcerProviderActor.props(policyEnforcerCache, blockedNamespaces,
                        pubSubMediator, cacheDispatcher));
    }

    @Override
    public CompletionStage<Optional<PolicyEnforcer>> getPolicyEnforcer(@Nullable final PolicyId policyId) {
        if (policyId == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return Patterns.ask(cachingPolicyEnforcerProviderActor, policyId, LOCAL_POLICY_RETRIEVAL_TIMEOUT)
                .thenApply(response -> {
                    final Optional<PolicyEnforcer> result;
                    if (response instanceof Optional<?> optional) {
                        result = optional.map(value -> {
                            if (value instanceof PolicyEnforcer policyEnforcer) {
                                return policyEnforcer;
                            } else {
                                LOGGER.warn("Did receive Optional holding an unexpected type. " +
                                                "Did expect a PolicyEnforcer but got <{}>.",
                                        value.getClass());
                                return null;
                            }
                        });
                    } else {
                        result = Optional.empty();
                    }
                    return result;
                });
    }

    @Override
    public CompletionStage<Boolean> invalidate(final PolicyTag policyTag, final String correlationId,
            final Duration askTimeout) {
        return Patterns.ask(cachingPolicyEnforcerProviderActor, new PolicyTagEnvelope(policyTag, correlationId),
                        askTimeout)
                .thenApply(result -> {
                    if (result instanceof Boolean invalidated) {
                        return invalidated;
                    }
                    throw DittoInternalErrorException.fromMessage(
                            "Unexpected cachingPolicyEnforcerProviderActor response",
                            DittoHeaders.newBuilder().correlationId(correlationId).build());
                });
    }

    protected record PolicyTagEnvelope(PolicyTag policyTag, String correlationId){}

    /**
     * Actor which handles the actual cache lookup and invalidation.
     */
    private static final class CachingPolicyEnforcerProviderActor extends AbstractActor {

        private final DittoDiagnosticLoggingAdapter log = DittoLoggerFactory.getDiagnosticLoggingAdapter(this);
        private final PolicyEnforcerCache policyEnforcerCache;
        private final MessageDispatcher cacheDispatcher;

        CachingPolicyEnforcerProviderActor(final PolicyEnforcerCache policyEnforcerCache,
                @Nullable final BlockedNamespaces blockedNamespaces,
                final ActorRef pubSubMediator,
                final MessageDispatcher cacheDispatcher) {

            this.policyEnforcerCache = policyEnforcerCache;
            this.cacheDispatcher = cacheDispatcher;

            if (blockedNamespaces != null) {
                blockedNamespaces.subscribeForChanges(getSelf());
            }

            // subscribe for PolicyTags in order to reload policyEnforcer when "backing policy" was modified
            pubSubMediator.tell(DistPubSubAccess.subscribe(PolicyTag.PUB_SUB_TOPIC_INVALIDATE_ENFORCERS, getSelf()),
                    getSelf());
        }

        private static Props props(final PolicyEnforcerCache policyEnforcerCache,
                @Nullable final BlockedNamespaces blockedNamespaces,
                final ActorRef pubSubMediator, final MessageDispatcher cacheDispatcher) {

            return Props.create(CachingPolicyEnforcerProviderActor.class, policyEnforcerCache, blockedNamespaces,
                    pubSubMediator, cacheDispatcher);
        }

        @Override
        public Receive createReceive() {
            return ReceiveBuilder.create()
                    .match(PolicyId.class, this::doGetPolicyEnforcer)
                    .match(DistributedPubSubMediator.SubscribeAck.class, s -> log.debug("Got subscribeAck <{}>.", s))
                    .match(PolicyTag.class, policyTag -> policyEnforcerCache.invalidate(policyTag.getEntityId()))
                    .match(PolicyTagEnvelope.class, policyTagEnvelope -> {
                        log.withCorrelationId(policyTagEnvelope.correlationId()).debug(policyTagEnvelope.correlationId());
                        final boolean invalidated =
                                policyEnforcerCache.invalidate(policyTagEnvelope.policyTag().getEntityId());
                        getSender().tell(invalidated, getSelf());
                    })
                    .match(Replicator.Changed.class, this::handleChangedBlockedNamespaces)
                    .build();
        }

        private void doGetPolicyEnforcer(final PolicyId policyId) {
            final ActorRef sender = getSender();
            final CompletableFuture<Optional<PolicyEnforcer>> policyEnforcerCS =
                    policyEnforcerCache.get(policyId).thenApply(optionalEntry -> optionalEntry.flatMap(Entry::get));
            Patterns.pipe(policyEnforcerCS, cacheDispatcher).to(sender);
        }

        @SuppressWarnings("unchecked")
        private void handleChangedBlockedNamespaces(final Replicator.Changed<?> changed) {
            if (changed.dataValue() instanceof ORSet<?> orSet) {
                final ORSet<String> namespaces = (ORSet<String>) orSet;
                logNamespaces("Received", namespaces);
                policyEnforcerCache.asMap().keySet().stream()
                        .filter(policyId -> {
                            final String cachedNamespace = policyId.getNamespace();
                            return namespaces.contains(cachedNamespace);
                        })
                        .forEach(policyEnforcerCache::invalidate);
            } else {
                log.warning("Unhandled: <{}>", changed);
            }
        }

        private void logNamespaces(final String verb, final ORSet<String> namespaces) {
            if (namespaces.size() > 25) {
                log.info("{} <{}> namespaces", verb, namespaces.size());
            } else {
                log.info("{} namespaces: <{}>", verb, namespaces);
            }
        }

    }

}
