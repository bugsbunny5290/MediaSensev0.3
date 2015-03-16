/*
 * Copyright 2011 Thomas Bocek
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package net.tomp2p.p2p;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.tomp2p.connection.ChannelCreator;
import net.tomp2p.connection.ConnectionReservation;
import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.FutureChannelCreator;
import net.tomp2p.futures.FutureCreate;
import net.tomp2p.futures.FutureDHT;
import net.tomp2p.futures.FutureForkJoin;
import net.tomp2p.futures.FutureResponse;
import net.tomp2p.futures.FutureRouting;
import net.tomp2p.message.Message.Type;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.Number480;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.rpc.DigestInfo;
import net.tomp2p.rpc.DigestResult;
import net.tomp2p.rpc.DirectDataRPC;
import net.tomp2p.rpc.SimpleBloomFilter;
import net.tomp2p.rpc.StorageRPC;
import net.tomp2p.storage.Data;
import net.tomp2p.utils.Utils;

import org.jboss.netty.buffer.ChannelBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DistributedHashTable {
    final private static Logger logger = LoggerFactory.getLogger(DistributedHashTable.class);

    final private DistributedRouting routing;

    final private StorageRPC storeRCP;

    final private DirectDataRPC directDataRPC;

    public DistributedHashTable(DistributedRouting routing, StorageRPC storeRCP, DirectDataRPC directDataRPC) {
        this.routing = routing;
        this.storeRCP = storeRCP;
        this.directDataRPC = directDataRPC;
    }

    public FutureDHT add(final Number160 locationKey, final Number160 domainKey, final Collection<Data> dataSet,
            final RoutingConfiguration routingConfiguration, final RequestP2PConfiguration p2pConfiguration,
            final boolean protectDomain, final boolean signMessage, final boolean isManualCleanup, final boolean list,
            final FutureCreate<FutureDHT> futureCreate, final FutureChannelCreator futureChannelCreator,
            final ConnectionReservation connectionReservation) {
        final FutureDHT futureDHT = new FutureDHT(p2pConfiguration.getMinimumResults(), new VotingSchemeDHT(),
                futureCreate);

        futureChannelCreator.addListener(new BaseFutureAdapter<FutureChannelCreator>() {
            @Override
            public void operationComplete(final FutureChannelCreator future) throws Exception {
                if (future.isSuccess()) {
                    final FutureRouting futureRouting = createRouting(locationKey, domainKey, null,
                            routingConfiguration, p2pConfiguration, Type.REQUEST_1, future.getChannelCreator());
                    futureDHT.setFutureRouting(futureRouting);
                    futureRouting.addListener(new BaseFutureAdapter<FutureRouting>() {
                        @Override
                        public void operationComplete(FutureRouting futureRouting) throws Exception {
                            if (futureRouting.isSuccess()) {
                                if (logger.isDebugEnabled())
                                    logger.debug("adding lkey=" + locationKey + " on "
                                            + futureRouting.getPotentialHits());
                                parallelRequests(p2pConfiguration, futureRouting.getPotentialHits(), futureDHT, false,
                                        future.getChannelCreator(), new Operation() {
                                            Map<PeerAddress, Collection<Number160>> rawData = new HashMap<PeerAddress, Collection<Number160>>();

                                            Map<PeerAddress, Collection<Number480>> rawData480 = new HashMap<PeerAddress, Collection<Number480>>();

                                            @Override
                                            public FutureResponse create(ChannelCreator channelCreator,
                                                    PeerAddress address) {
                                                return storeRCP.add(address, locationKey, domainKey, dataSet,
                                                        protectDomain, signMessage, list, channelCreator,
                                                        p2pConfiguration.isForceUPD(),
                                                        p2pConfiguration.getSenderCacheStrategy());
                                            }

                                            @Override
                                            public void response(FutureDHT futureDHT) {
                                                futureDHT.setStoredKeys(locationKey, domainKey, rawData, rawData480);
                                            }

                                            @Override
                                            public void interMediateResponse(FutureResponse future) {
                                                // the future
                                                // tells us that
                                                // the
                                                // communication
                                                // was
                                                // successful,
                                                // but we need
                                                // to check the
                                                // result if we
                                                // could store
                                                // it.
                                                if (future.isSuccess() && future.getResponse().isOk()) {
                                                    rawData.put(future.getRequest().getRecipient(), future
                                                            .getResponse().getKeys());
                                                    rawData480.put(future.getRequest().getRecipient(), future
                                                            .getResponse().getKeys480());
                                                }
                                            }
                                        });
                                if (futureDHT.isReleaseEarly()) {
                                    Utils.addReleaseListenerAll(futureDHT, connectionReservation,
                                            future.getChannelCreator());
                                }
                            } else {
                                futureDHT.setFailed("routing failed");
                            }
                        }
                    });

                    if (!isManualCleanup && !futureDHT.isReleaseEarly()) {
                        Utils.addReleaseListenerAll(futureDHT, connectionReservation, future.getChannelCreator());
                    }
                } else {
                    futureDHT.setFailed(future);
                }
            }
        });
        return futureDHT;
    }

    public FutureDHT direct(final Number160 locationKey, final ChannelBuffer buffer, final boolean raw,
            final RoutingConfiguration routingConfiguration, final RequestP2PConfiguration p2pConfiguration,
            final FutureCreate<FutureDHT> futureCreate, final boolean cancelOnFinish, final boolean manualCleanup,
            final FutureChannelCreator futureChannelCreator, final ConnectionReservation connectionReservation) {
        final FutureDHT futureDHT = new FutureDHT(p2pConfiguration.getMinimumResults(), new VotingSchemeDHT(),
                futureCreate);

        futureChannelCreator.addListener(new BaseFutureAdapter<FutureChannelCreator>() {
            @Override
            public void operationComplete(final FutureChannelCreator future) throws Exception {
                if (future.isSuccess()) {
                    final FutureRouting futureRouting = createRouting(locationKey, null, null, routingConfiguration,
                            p2pConfiguration, Type.REQUEST_1, future.getChannelCreator());
                    futureDHT.setFutureRouting(futureRouting);
                    futureRouting.addListener(new BaseFutureAdapter<FutureRouting>() {
                        @Override
                        public void operationComplete(FutureRouting futureRouting) throws Exception {
                            if (futureRouting.isSuccess()) {
                                if (logger.isDebugEnabled())
                                    logger.debug("storing lkey=" + locationKey + " on "
                                            + futureRouting.getPotentialHits());
                                parallelRequests(p2pConfiguration, futureRouting.getPotentialHits(), futureDHT,
                                        cancelOnFinish, future.getChannelCreator(), new Operation() {
                                            Map<PeerAddress, ChannelBuffer> rawChannels = new HashMap<PeerAddress, ChannelBuffer>();

                                            Map<PeerAddress, Object> rawObjects = new HashMap<PeerAddress, Object>();

                                            @Override
                                            public FutureResponse create(ChannelCreator channelCreator,
                                                    PeerAddress address) {
                                                return directDataRPC.send(address, buffer, raw, channelCreator,
                                                        p2pConfiguration.isForceUPD());
                                            }

                                            @Override
                                            public void response(FutureDHT futureDHT) {
                                                if (raw)
                                                    futureDHT.setDirectData1(rawChannels);
                                                else
                                                    futureDHT.setDirectData2(rawObjects);
                                            }

                                            @Override
                                            public void interMediateResponse(FutureResponse future) {
                                                // the future
                                                // tells us that
                                                // the
                                                // communication
                                                // was
                                                // successful,
                                                // but we need
                                                // to check the
                                                // result if we
                                                // could store
                                                // it.
                                                if (future.isSuccess() && future.getResponse().isOk()) {
                                                    if (raw) {
                                                        rawChannels.put(future.getRequest().getRecipient(),
                                                                future.getBuffer());
                                                    } else {
                                                        rawObjects.put(future.getRequest().getRecipient(),
                                                                future.getObject());
                                                    }
                                                }
                                            }
                                        });
                            } else {
                                futureDHT.setFailed("routing failed");
                            }
                        }
                    });
                    if (!manualCleanup) {
                        Utils.addReleaseListenerAll(futureDHT, connectionReservation, future.getChannelCreator());
                    }
                } else {
                    futureDHT.setFailed(future);
                }
            }
        });

        return futureDHT;
    }

    public FutureDHT put(final Number160 locationKey, final Number160 domainKey, final Map<Number160, Data> dataMap,
            final RoutingConfiguration routingConfiguration, final RequestP2PConfiguration p2pConfiguration,
            final boolean putIfAbsent, final boolean protectDomain, final boolean signMessage,
            final boolean isManualCleanup, final FutureCreate<FutureDHT> futureCreate,
            final FutureChannelCreator futureChannelCreator, final ConnectionReservation connectionReservation) {
        final FutureDHT futureDHT = new FutureDHT(p2pConfiguration.getMinimumResults(), new VotingSchemeDHT(),
                futureCreate);
        futureChannelCreator.addListener(new BaseFutureAdapter<FutureChannelCreator>() {
            @Override
            public void operationComplete(final FutureChannelCreator future) throws Exception {
                if (future.isSuccess()) {
                    final FutureRouting futureRouting = createRouting(locationKey, domainKey, null,
                            routingConfiguration, p2pConfiguration, Type.REQUEST_1, future.getChannelCreator());
                    futureDHT.setFutureRouting(futureRouting);
                    futureRouting.addListener(new BaseFutureAdapter<FutureRouting>() {
                        @Override
                        public void operationComplete(FutureRouting futureRouting) throws Exception {
                            if (futureRouting.isSuccess()) {
                                if (logger.isDebugEnabled())
                                    logger.debug("storing lkey=" + locationKey + " on "
                                            + futureRouting.getPotentialHits());
                                parallelRequests(p2pConfiguration, futureRouting.getPotentialHits(), futureDHT, false,
                                        future.getChannelCreator(), new Operation() {
                                            Map<PeerAddress, Collection<Number160>> rawData = new HashMap<PeerAddress, Collection<Number160>>();

                                            Map<PeerAddress, Collection<Number480>> rawData480 = new HashMap<PeerAddress, Collection<Number480>>();

                                            @Override
                                            public FutureResponse create(ChannelCreator channelCreator,
                                                    PeerAddress address) {
                                                boolean protectEntry = Utils.checkEntryProtection(dataMap);
                                                return putIfAbsent ? storeRCP.putIfAbsent(address, locationKey,
                                                        domainKey, dataMap, protectDomain, protectEntry, signMessage,
                                                        channelCreator, p2pConfiguration.isForceUPD(),
                                                        p2pConfiguration.getSenderCacheStrategy()) : storeRCP.put(
                                                        address, locationKey, domainKey, dataMap, protectDomain,
                                                        protectEntry, signMessage, channelCreator,
                                                        p2pConfiguration.isForceUPD(),
                                                        p2pConfiguration.getSenderCacheStrategy());
                                            }

                                            @Override
                                            public void response(FutureDHT futureDHT) {
                                                futureDHT.setStoredKeys(locationKey, domainKey, rawData, rawData480);
                                            }

                                            @Override
                                            public void interMediateResponse(FutureResponse future) {
                                                // the future
                                                // tells us that
                                                // the
                                                // communication
                                                // was
                                                // successful,
                                                // but we need
                                                // to check the
                                                // result if we
                                                // could store
                                                // it.
                                                if (future.isSuccess() && future.getResponse().isOk()) {
                                                    rawData.put(future.getRequest().getRecipient(), future
                                                            .getResponse().getKeys());
                                                    rawData480.put(future.getRequest().getRecipient(), future
                                                            .getResponse().getKeys480());
                                                }
                                            }
                                        });
                                if (futureDHT.isReleaseEarly()) {
                                    connectionReservation.release(future.getChannelCreator());
                                }
                            } else {
                                futureDHT.setFailed("routing failed");
                            }
                        }
                    });
                    if (!isManualCleanup) // &&
                                          // !futureDHT.isReleaseEarly())
                    {
                        Utils.addReleaseListenerAll(futureDHT, connectionReservation, future.getChannelCreator());
                    }
                } else {
                    futureDHT.setFailed(future);
                }
            }
        });
        return futureDHT;
    }

    public FutureDHT get(final Number160 locationKey, final Number160 domainKey,
            final Collection<Number160> contentKeys, final SimpleBloomFilter<Number160> keyBloomFilter,
            final SimpleBloomFilter<Number160> contentBloomFilter, final RoutingConfiguration routingConfiguration,
            final RequestP2PConfiguration p2pConfiguration, final EvaluatingSchemeDHT evaluationScheme,
            final boolean signMessage, final boolean digest, final boolean returnBloomFilter, final boolean range,
            final boolean isManualCleanup, final FutureChannelCreator futureChannelCreator,
            final ConnectionReservation connectionReservation) {
        final FutureDHT futureDHT = new FutureDHT(p2pConfiguration.getMinimumResults(), evaluationScheme, null);
        futureChannelCreator.addListener(new BaseFutureAdapter<FutureChannelCreator>() {
            @Override
            public void operationComplete(final FutureChannelCreator future) throws Exception {
                if (future.isSuccess()) {
                    final FutureRouting futureRouting = createRouting(locationKey, domainKey, contentKeys,
                            routingConfiguration, p2pConfiguration, Type.REQUEST_2, future.getChannelCreator());
                    futureDHT.setFutureRouting(futureRouting);
                    futureRouting.addListener(new BaseFutureAdapter<FutureRouting>() {
                        @Override
                        public void operationComplete(FutureRouting futureRouting) throws Exception {
                            if (futureRouting.isSuccess()) {
                                if (logger.isDebugEnabled()) {
                                    logger.debug("found direct hits for get: " + futureRouting.getDirectHits());
                                }
                                // this adjust is based on
                                // results from the routing
                                // process,
                                // if we find the same data on 2
                                // peers, we want to get it from
                                // one only. Unless its
                                // digest, then we want to know
                                // exactly what is going on
                                RequestP2PConfiguration p2pConfiguration2 = digest || range ? p2pConfiguration
                                        : adjustConfiguration(p2pConfiguration, futureRouting.getDirectHitsDigest());
                                // store in direct hits
                                parallelRequests(p2pConfiguration2, range ? futureRouting.getPotentialHits()
                                        : futureRouting.getDirectHits(), futureDHT, true, future.getChannelCreator(),
                                        new Operation() {
                                            Map<PeerAddress, Map<Number160, Data>> rawData = new HashMap<PeerAddress, Map<Number160, Data>>();

                                            Map<PeerAddress, DigestResult> rawDigest = new HashMap<PeerAddress, DigestResult>();

                                            @Override
                                            public FutureResponse create(ChannelCreator channelCreator,
                                                    PeerAddress address) {
                                                return storeRCP.get(address, locationKey, domainKey, contentKeys,
                                                        keyBloomFilter, contentBloomFilter, signMessage, digest,
                                                        returnBloomFilter, range, channelCreator,
                                                        p2pConfiguration.isForceUPD());
                                            }

                                            @Override
                                            public void response(FutureDHT futureDHT) {
                                                if (digest) {
                                                    futureDHT.setReceivedDigest(locationKey, domainKey,rawDigest);
                                                } else {
                                                    futureDHT.setReceivedData(locationKey, domainKey,rawData);
                                                }
                                            }

                                            @Override
                                            public void interMediateResponse(FutureResponse future) {
                                                // the future
                                                // tells us that
                                                // the
                                                // communication
                                                // was
                                                // successful,
                                                // which is ok
                                                // for digest
                                                if (future.isSuccess()) {
                                                    if (digest) {
                                                        SimpleBloomFilter<Number160> sbf1 = future.getResponse()
                                                                .getBloomFilter1();
                                                        SimpleBloomFilter<Number160> sbf2 = future.getResponse()
                                                                .getBloomFilter1();
                                                        final DigestResult digest;
                                                        if (sbf1 == null && sbf2 == null) {
                                                            Map<Number160, Number160> keyDigest = future.getResponse()
                                                                    .getKeyMap();
                                                            digest = new DigestResult(keyDigest);
                                                        } else {
                                                            digest = new DigestResult(sbf1, sbf2);
                                                        }

                                                        rawDigest.put(future.getRequest().getRecipient(), digest);
                                                    } else {
                                                        rawData.put(future.getRequest().getRecipient(), future
                                                                .getResponse().getDataMap());
                                                    }
                                                }

                                            }
                                        });
                            } else {
                                futureDHT.setFailed("routing failed");
                            }
                        }
                    });
                    if (!isManualCleanup) {
                        Utils.addReleaseListenerAll(futureDHT, connectionReservation, future.getChannelCreator());
                    }
                } else {
                    futureDHT.setFailed(future);
                }
            }
        });
        return futureDHT;
    }

    public FutureDHT remove(final Number160 locationKey, final Number160 domainKey,
            final Collection<Number160> contentKeys, final RoutingConfiguration routingConfiguration,
            final RequestP2PConfiguration p2pConfiguration, final boolean returnResults, final boolean signMessage,
            final boolean isManualCleanup, FutureCreate<FutureDHT> futureCreate,
            final FutureChannelCreator futureChannelCreator, final ConnectionReservation connectionReservation) {
        final FutureDHT futureDHT = new FutureDHT(p2pConfiguration.getMinimumResults(), new VotingSchemeDHT(),
                futureCreate);

        futureChannelCreator.addListener(new BaseFutureAdapter<FutureChannelCreator>() {
            @Override
            public void operationComplete(final FutureChannelCreator future) throws Exception {
                if (future.isSuccess()) {
                    final FutureRouting futureRouting = createRouting(locationKey, domainKey, contentKeys,
                            routingConfiguration, p2pConfiguration, Type.REQUEST_2, future.getChannelCreator());
                    futureDHT.setFutureRouting(futureRouting);
                    futureRouting.addListener(new BaseFutureAdapter<FutureRouting>() {
                        @Override
                        public void operationComplete(FutureRouting futureRouting) throws Exception {
                            if (futureRouting.isSuccess()) {
                                if (logger.isDebugEnabled())
                                    logger.debug("found direct hits for remove: " + futureRouting.getDirectHits());
                                parallelRequests(p2pConfiguration, futureRouting.getDirectHits(), futureDHT, false,
                                        future.getChannelCreator(), new Operation() {
                                            Map<PeerAddress, Map<Number160, Data>> rawDataResult = new HashMap<PeerAddress, Map<Number160, Data>>();

                                            Map<PeerAddress, Collection<Number160>> rawDataNoResult = new HashMap<PeerAddress, Collection<Number160>>();

                                            @Override
                                            public FutureResponse create(ChannelCreator channelCreator,
                                                    PeerAddress address) {
                                                return storeRCP.remove(address, locationKey, domainKey, contentKeys,
                                                        returnResults, signMessage, channelCreator,
                                                        p2pConfiguration.isForceUPD());
                                            }

                                            @Override
                                            public void response(FutureDHT futureDHT) {
                                                if (returnResults)
                                                    futureDHT.setReceivedData(locationKey, domainKey, rawDataResult);
                                                else
                                                    futureDHT.setRemovedKeys(locationKey, domainKey, rawDataNoResult);
                                            }

                                            @Override
                                            public void interMediateResponse(FutureResponse future) {
                                                // the future
                                                // tells us that
                                                // the
                                                // communication
                                                // was
                                                // successful,
                                                // but we need
                                                // to check the
                                                // result if we
                                                // could store
                                                // it.
                                                if (future.isSuccess() && future.getResponse().isOk()) {
                                                    if (returnResults) {
                                                        rawDataResult.put(future.getRequest().getRecipient(), future
                                                                .getResponse().getDataMap());
                                                    } else {
                                                        rawDataNoResult.put(future.getRequest().getRecipient(), future
                                                                .getResponse().getKeys());
                                                    }
                                                }
                                            }
                                        });
                            } else
                                futureDHT.setFailed("routing failed");
                        }
                    });
                    if (!isManualCleanup) {
                        Utils.addReleaseListenerAll(futureDHT, connectionReservation, future.getChannelCreator());
                    }
                } else {
                    futureDHT.setFailed(future);
                }

            }
        });
        return futureDHT;
    }

    /**
     * Creates RPCs and executes them parallel.
     * 
     * @param p2pConfiguration
     *            The configuration that specifies e.g. how many parallel
     *            requests there are.
     * @param queue
     *            The sorted set that will be queries. The first RPC takes the
     *            first in the queue.
     * @param futureDHT
     *            The future object that tracks the progress
     * @param cancleOnFinish
     *            Set to true if the operation should be canceled (e.g. file
     *            transfer) if the future has finished.
     * @param operation
     *            The operation that creates the request
     */
    public FutureDHT parallelRequests(final RequestP2PConfiguration p2pConfiguration,
            final NavigableSet<PeerAddress> queue, final boolean cancleOnFinish,
            final FutureChannelCreator futureChannelCreator, final ConnectionReservation connectionReservation,
            final boolean manualCleanup, final Operation operation) {
        final FutureDHT futureDHT = new FutureDHT(p2pConfiguration.getMinimumResults(), new VotingSchemeDHT(), null);

        futureChannelCreator.addListener(new BaseFutureAdapter<FutureChannelCreator>() {
            @Override
            public void operationComplete(final FutureChannelCreator future) throws Exception {
                if (future.isSuccess()) {
                    parallelRequests(p2pConfiguration, queue, futureDHT, cancleOnFinish, future.getChannelCreator(),
                            operation);
                    if (!manualCleanup) {
                        Utils.addReleaseListenerAll(futureDHT, connectionReservation, future.getChannelCreator());
                    }
                } else {
                    futureDHT.setFailed(future);
                }
            }
        });
        return futureDHT;
    }

    private void parallelRequests(RequestP2PConfiguration p2pConfiguration, NavigableSet<PeerAddress> queue,
            FutureDHT futureDHT, boolean cancleOnFinish, ChannelCreator channelCreator, Operation operation) {
        if (p2pConfiguration.getMinimumResults() == 0) {
            operation.response(futureDHT);
            return;
        }
        FutureResponse[] futures = new FutureResponse[p2pConfiguration.getParallel()];
        // here we split min and pardiff, par=min+pardiff
        loopRec(queue, p2pConfiguration.getMinimumResults(), new AtomicInteger(0), p2pConfiguration.getMaxFailure(),
                p2pConfiguration.getParallelDiff(), futures, futureDHT, cancleOnFinish, channelCreator, operation);
    }

    private void loopRec(final NavigableSet<PeerAddress> queue, final int min, final AtomicInteger nrFailure,
            final int maxFailure, final int parallelDiff, final FutureResponse[] futures, final FutureDHT futureDHT,
            final boolean cancelOnFinish, final ChannelCreator channelCreator, final Operation operation) {
        // final int parallel=min+parallelDiff;
        int active = 0;
        int shared = 0;
        for (int i = 0; i < min + parallelDiff; i++) {
            if (futures[i] == null) {
                PeerAddress next = queue.pollFirst();
                if (next != null) {
                    active++;
                    futures[i] = operation.create(channelCreator, next);
                    futureDHT.addRequests(futures[i]);
                    if (futures[i].isShared()) {
                        shared++;
                    }
                }
            } else {
                active++;
            }
        }
        // special case, where we know that we won't have further requests
        if (maxFailure == 0 && shared == min + parallelDiff) {
            futureDHT.releaseEarly();
        }
        if (active == 0) {
            operation.response(futureDHT);
            DistributedRouting.cancel(cancelOnFinish, min + parallelDiff, futures);
            return;
        }
        if (logger.isDebugEnabled())
            logger.debug("fork/join status: " + min + "/" + active + " (" + parallelDiff + ")");
        FutureForkJoin<FutureResponse> fp = new FutureForkJoin<FutureResponse>(Math.min(min, active), false, futures);
        fp.addListener(new BaseFutureAdapter<FutureForkJoin<FutureResponse>>() {
            @Override
            public void operationComplete(FutureForkJoin<FutureResponse> future) throws Exception {
                for (FutureResponse futureResponse : future.getCompleted()) {
                    operation.interMediateResponse(futureResponse);
                }
                // we are finished if forkjoin says so or we got too many
                // failures
                if (future.isSuccess() || nrFailure.incrementAndGet() > maxFailure) {
                    if (cancelOnFinish) {
                        DistributedRouting.cancel(cancelOnFinish, min + parallelDiff, futures);
                    }
                    operation.response(futureDHT);
                } else {
                    loopRec(queue, min - future.getSuccessCounter(), nrFailure, maxFailure, parallelDiff, futures,
                            futureDHT, cancelOnFinish, channelCreator, operation);
                }
            }
        });
    }

    private FutureRouting createRouting(Number160 locationKey, Number160 domainKey, Collection<Number160> contentKeys,
            RoutingConfiguration routingConfiguration, RequestP2PConfiguration p2pConfiguration, Type type,
            ChannelCreator channelCreator) {
        return routing.route(locationKey, domainKey, contentKeys, type, routingConfiguration.getDirectHits(),
                routingConfiguration.getMaxNoNewInfo(p2pConfiguration.getMinimumResults()),
                routingConfiguration.getMaxFailures(), routingConfiguration.getMaxSuccess(),
                routingConfiguration.getParallel(), routingConfiguration.isForceTCP(), channelCreator);
    }

    public interface Operation {
        public abstract FutureResponse create(ChannelCreator channelCreator, PeerAddress remotePeerAddress);

        public abstract void response(FutureDHT futureDHT);

        public abstract void interMediateResponse(FutureResponse futureResponse);
    }

    /**
     * Adjusts the number of minimum requests in the P2P configuration. When we
     * query x peers for the get() operation and they have y different data
     * stored (y <= x), then set the minimum to y or to the value the user set
     * if its smaller. If no data is found, then return 0, so we don't start P2P
     * RPCs.
     * 
     * @param p2pConfiguration
     *            The old P2P configuration with the user specified minimum
     *            result
     * @param directHitsDigest
     *            The digest information from the routing process
     * @return The new RequestP2PConfiguration with the new minimum result
     */
    public static RequestP2PConfiguration adjustConfiguration(final RequestP2PConfiguration p2pConfiguration,
            final SortedMap<PeerAddress, DigestInfo> directHitsDigest) {
        
        int size = directHitsDigest.size();
        int requested = p2pConfiguration.getMinimumResults();
        if (size >= requested) {
            return p2pConfiguration;
        } else {
            return new RequestP2PConfiguration(size, p2pConfiguration.getMaxFailure(),
                    p2pConfiguration.getParallelDiff());
        }
    }
}
