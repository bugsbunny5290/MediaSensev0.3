/*
 * Copyright 2009 Thomas Bocek
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
package net.tomp2p.peers;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import net.tomp2p.p2p.Statistics;
import net.tomp2p.peers.PeerStatusListener.Reason;
import net.tomp2p.utils.CacheMap;
import net.tomp2p.utils.Timings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This routing implementation uses is based on Kademlia. However, many changes have been applied to make it faster and
 * more flexible. This class is partially thread-safe.
 * 
 * @author Thomas Bocek
 */
public class PeerMap {
    final private static Logger logger = LoggerFactory.getLogger(PeerMap.class);

    // each distance bit has its own bag, which can grow.
    final private int bagSize;

    // the maximum total peers in all bags
    final private int maxPeers;

    // the id of this node
    final private Number160 self;

    // go for variable bag size. Much more performance for small networks
    final private List<Map<Number160, PeerAddress>> peerMap;

    // In this bag, peers are temporarily stored that have been removed in order
    // to not reappear again.
    final private Map<PeerAddress, Log> peerOfflineLogs;

    // the timeout of the removed peers to stay in the removedPeerCache
    final private int cacheTimeout;

    final private int maxFail;

    // counts the number of peers, this is faster than iterating and counting.
    final private AtomicInteger peerCount = new AtomicInteger();

    // stores listeners that will be notified if a peer gets removed or added
    final private List<PeerMapChangeListener> peerMapChangeListeners = new ArrayList<PeerMapChangeListener>();

    final private List<PeerStatusListener> peerListeners = new ArrayList<PeerStatusListener>();

    final private int[] maintenanceTimeoutsSeconds;

    final private Map<PeerAddress, Long> maintenance = new LinkedHashMap<PeerAddress, Long>();

    final private Collection<InetAddress> filteredAddresses = Collections.synchronizedSet(new HashSet<InetAddress>());

    final private PeerMapStat peerMapStat;

    final private Statistics statistics;

    final private MapAcceptHandler mapHandler;

    class Log {
        private int counter;

        private long lastOffline;

        private void inc() {
            counter++;
            lastOffline = Timings.currentTimeMillis();
        }

        private void set(int counter) {
            this.counter = counter;
            lastOffline = Timings.currentTimeMillis();
        }

        private int getCounter() {
            return counter;
        }

        private long getLastOffline() {
            return lastOffline;
        }
    }

    @Deprecated
    public PeerMap(final Number160 self, int bagSize, int cacheTimeoutMillis, int maxNrBeforeExclude,
            int[] waitingTimeBetweenNodeMaintenenceSeconds, int cachSize, boolean acceptFirstClassOnly) {
        this(self, bagSize, cacheTimeoutMillis, maxNrBeforeExclude, waitingTimeBetweenNodeMaintenenceSeconds, cachSize,
                new DefaultMapAcceptHandler(acceptFirstClassOnly));
    }

    /**
     * Creates the bag for the peers. This peer knows a lot about close peers and the further away the peers are, the
     * less known they are. Distance is measured with XOR of the peer ID. The distance of peer with ID 0x12 and peer
     * with Id 0x28 is 0x3a.
     * 
     * @param self
     *            The peer ID of this peer
     * @param configuration
     *            Configuration settings for this map
     */
    public PeerMap(final Number160 self, int bagSize, int cacheTimeoutMillis, int maxNrBeforeExclude,
            int[] waitingTimeBetweenNodeMaintenenceSeconds, int cachSize, MapAcceptHandler mapHandler) {
        if (self == null || self.isZero())
            throw new IllegalArgumentException("Zero or null are not a valid IDs");
        this.self = self;
        this.peerMapStat = new PeerMapStat();
        // The original Kademlia suggests 20, butwe can go much lower, as we
        // dont have a fixed limit for a bag. The bagSize is a suggestion and if
        // maxpeers has not been reached, the peer is added even though it
        // exceeds the bag limit.
        this.bagSize = bagSize;
        this.maxPeers = bagSize * Number160.BITS;
        // The time that a removed peer will be in the cache in milliseconds.
        this.cacheTimeout = cacheTimeoutMillis;
        this.maxFail = maxNrBeforeExclude;
        this.maintenanceTimeoutsSeconds = waitingTimeBetweenNodeMaintenenceSeconds;
        // The size of the cache of removed peers
        this.peerOfflineLogs = new CacheMap<PeerAddress, Log>(cachSize, false);
        this.mapHandler = mapHandler;
        List<Map<Number160, PeerAddress>> tmp = new ArrayList<Map<Number160, PeerAddress>>();
        for (int i = 0; i < Number160.BITS; i++) {
            // I made some experiments here and concurrent sets are not
            // necessary, as we divide similar to segments aNonBlockingHashSets
            // in a
            // concurrent map. In a full network, we have 160 segments, for
            // smaller we see around 3-4 segments, growing with the number of
            // peers. bags closer to 0 will see more read than write, and bags
            // closer to 160 will see more writes than reads.
            tmp.add(Collections.<Number160, PeerAddress> synchronizedMap(new HashMap<Number160, PeerAddress>()));
        }
        this.peerMap = Collections.unmodifiableList(tmp);
        this.statistics = new Statistics(peerMap, self, maxPeers, bagSize);
    }

    public void addPeerMapChangeListener(PeerMapChangeListener peerMapChangeListener) {
        peerMapChangeListeners.add(peerMapChangeListener);
    }

    public void removePeerMapChangeListener(PeerMapChangeListener peerMapChangeListener) {
        peerMapChangeListeners.add(peerMapChangeListener);
    }

    public void addPeerOfflineListener(PeerStatusListener peerListener) {
        // synchronized should be ok, since we dont call addListener too often
        synchronized (peerListeners) {
            peerListeners.add(peerListener);
        }

    }

    public void removePeerOfflineListener(PeerStatusListener peerListener) {
        // synchronized should be ok, since we dont call addListener too often
        synchronized (peerListeners) {
            peerListeners.remove(peerListener);
        }
    }

    public Statistics getStatistics() {
        return statistics;
    }

    /**
     * Notifies on insert. Since listeners are never changed, this is thread safe.
     * 
     * @param peerAddress
     *            The address of the inserted peers
     */
    private void notifyInsert(PeerAddress peerAddress) {
        statistics.triggerStatUpdate(true, size());
        for (PeerMapChangeListener listener : peerMapChangeListeners)
            listener.peerInserted(peerAddress);
    }

    /**
     * Notifies on remove. Since listeners are never changed, this is thread safe.
     * 
     * @param peerAddress
     *            The address of the removed peers
     */
    private void notifyRemove(PeerAddress peerAddress) {
        statistics.triggerStatUpdate(false, size());
        for (PeerMapChangeListener listener : peerMapChangeListeners)
            listener.peerRemoved(peerAddress);
    }

    /**
     * Notifies on update. This method is thread safe.
     * 
     * @param peerAddress
     */
    private void notifyUpdate(PeerAddress peerAddress) {
        for (PeerMapChangeListener listener : peerMapChangeListeners)
            listener.peerUpdated(peerAddress);
    }

    private void notifyOffline(PeerAddress peerAddress, Reason reason) {
        // synchronized should be ok, since we dont call addListener too often
        synchronized (peerListeners) {
            for (PeerStatusListener listener : peerListeners)
                listener.peerOffline(peerAddress, reason);
        }
    }

    private void notifyPeerFail(PeerAddress peerAddress, boolean force) {
        // synchronized should be ok, since we dont call addListener too often
        synchronized (peerListeners) {
            for (PeerStatusListener listener : peerListeners)
                listener.peerFail(peerAddress, force);
        }
    }

    private void notifyPeerOnline(PeerAddress peerAddress) {
        // synchronized should be ok, since we dont call addListener too often
        synchronized (peerListeners) {
            for (PeerStatusListener listener : peerListeners)
                listener.peerOnline(peerAddress);
        }
    }

    /**
     * The peerCount keeps track of the total number of peer in the system.
     * 
     * @return the total number of peers
     */
    public int size() {
        return peerCount.get();
    }

    /**
     * Each node that has a bag has an ID itself to define what is close. This method returns this ID.
     * 
     * @return The id of this node
     */
    public Number160 self() {
        return self;
    }

    /**
     * Adds a neighbor to the neighbor list. If the bag is full, the id zero or the same as our id, the neighbor is not
     * added. This method is tread-safe
     * 
     * @param remotePeer
     *            The node that should be added
     * @param referrer
     *            If we had direct contact and we know for sure that this node is online, we set firsthand to true.
     *            Information from 3rd party peers are always second hand and treated as such
     * @return True if the neighbor could be added or updated, otherwise false.
     */
    public boolean peerFound(final PeerAddress remotePeer, final PeerAddress referrer) {
        boolean firstHand = referrer == null;
        // always trust first hand information
        if (firstHand) {
            notifyPeerOnline(remotePeer);
            synchronized (peerOfflineLogs) {
                peerOfflineLogs.remove(remotePeer);
            }
        }
        // don't add nodes with zero node id, do not add myself and do not add
        // nodes marked as bad
        if (remotePeer.getID().isZero() || self().equals(remotePeer.getID()) || isPeerRemovedTemporarly(remotePeer)
                || filteredAddresses.contains(remotePeer.getInetAddress())) {
            return false;
        }
        // the peer might have a new port
        if (updateExistingPeerAddress(remotePeer)) {
            // we update the peer, so we can exit here and report that we have
            // updated it.
            return true;
        }
        // check if we have should accept this peer in our peer map.
        if (!mapHandler.acceptPeer(firstHand, remotePeer)) {
            return false;
        }

        final int classMember = classMember(remotePeer.getID());
        final Map<Number160, PeerAddress> map = peerMap.get(classMember);
        if (size() < maxPeers) {
            // this updates stats and schedules peer for maintenance
            prepareInsertOrUpdate(remotePeer, firstHand);
            // fill it in, regardless of the bag size, also update if we
            // already have this peer, we update the last seen time with
            // this
            return insertOrUpdate(map, remotePeer, classMember);
        } else {
            // the class is not full, remove other nodes!
            PeerAddress toRemove = removeLatestEntryExceedingBagSize();
            if (classMember(toRemove.getID()) > classMember(remotePeer.getID())) {
                if (remove(toRemove, Reason.REMOVED_FROM_MAP)) {
                    // this updates stats and schedules peer for maintenance
                    prepareInsertOrUpdate(remotePeer, firstHand);
                    return insertOrUpdate(map, remotePeer, classMember);
                }
            }
        }
        return false;
    }

    /**
     * Remove a peer from the list. In order to not reappear, the node is put for a certain time in a cache list to keep
     * the node removed. This method is thread-safe.
     * 
     * @param remotePeer
     *            The node that should be removed
     * @param force A flag that removes a peer immediately.
     * @return True if the neighbor was removed and added to a cache list. False if peer has not been removed or is
     *         already in the peer removed temporarly list.
     */
    public boolean peerOffline(final PeerAddress remotePeer, final boolean force) {
        if (logger.isDebugEnabled()) {
            logger.debug("peer " + remotePeer + " is offline");
        }
        if (remotePeer.getID().isZero() || self().equals(remotePeer.getID())) {
            return false;
        }
        notifyPeerFail(remotePeer, force);
        Log log;
        synchronized (peerOfflineLogs) {
            log = peerOfflineLogs.get(remotePeer);
            if (log == null) {
                log = new Log();
                peerOfflineLogs.put(remotePeer, log);
            }
        }
        synchronized (log) {
            if (!force) {
                if (shouldPeerBeRemoved(log)) {
                    remove(remotePeer, Reason.NOT_REACHABLE);
                    return true;
                }
                log.inc();
                if (!shouldPeerBeRemoved(log)) {
                    peerMapStat.removeStat(remotePeer);
                    addToMaintenanceQueue(remotePeer);
                    return false;
                }
            } else {
                log.set(maxFail);
            }
        }
        remove(remotePeer, Reason.NOT_REACHABLE);
        return true;
    }

    /**
     * Removes the peer from the neighbor list if present in the list. Notifies listeners that a peer is offline.
     * 
     * @param remotePeer
     *            The peer that has gone offline.
     * @param reason
     *            The reason for going offline
     * @return True if the peer was in our map and was removed.
     */
    private boolean remove(final PeerAddress remotePeer, final Reason reason) {
        final int classMember = classMember(remotePeer.getID());
        final Map<Number160, PeerAddress> map = peerMap.get(classMember);
        final boolean retVal = map.remove(remotePeer.getID()) != null;
        if (retVal) {
            removeFromMaintenance(remotePeer);
            peerCount.decrementAndGet();
            notifyRemove(remotePeer);
        }
        notifyOffline(remotePeer, reason);
        return retVal;
    }

    private void prepareInsertOrUpdate(PeerAddress remotePeer, boolean firstHand) {
        if (firstHand) {
            peerMapStat.setSeenOnlineTime(remotePeer);
            // get the amount of milliseconds for the online time
            long online = peerMapStat.online(remotePeer);
            // get the time we want to wait between maintenance checks
            if (maintenanceTimeoutsSeconds.length > 0) {
                int checked = peerMapStat.getChecked(remotePeer);
                if (checked >= maintenanceTimeoutsSeconds.length)
                    checked = maintenanceTimeoutsSeconds.length - 1;
                long time = maintenanceTimeoutsSeconds[checked] * 1000L;
                // if we have a higer online time than the maintenance time,
                // increase checked to increase the maintenace interval.
                if (online >= time) {
                    peerMapStat.incChecked(remotePeer);
                }
            }
        }
        addToMaintenanceQueue(remotePeer);
    }

    private void addToMaintenanceQueue(PeerAddress remotePeer) {
        if (maintenanceTimeoutsSeconds.length == 0)
            return;
        long scheduledCheck;
        if (peerMapStat.getLastSeenOnlineTime(remotePeer) == 0) {
            // we need to check now!
            scheduledCheck = Timings.currentTimeMillis();
        } else {
            // check for next schedule
            int checked = peerMapStat.getChecked(remotePeer);
            if (checked >= maintenanceTimeoutsSeconds.length)
                checked = maintenanceTimeoutsSeconds.length - 1;
            scheduledCheck = Timings.currentTimeMillis() + (maintenanceTimeoutsSeconds[checked] * 1000L);
        }
        synchronized (maintenance) {
            maintenance.put(remotePeer, scheduledCheck);
        }
    }

    public Collection<PeerAddress> peersForMaintenance() {
        Collection<PeerAddress> result = new ArrayList<PeerAddress>();
        final long now = Timings.currentTimeMillis();
        synchronized (maintenance) {
            for (Iterator<Map.Entry<PeerAddress, Long>> iterator = maintenance.entrySet().iterator(); iterator
                    .hasNext();) {
                Map.Entry<PeerAddress, Long> entry = iterator.next();
                if (entry.getValue() <= now) {
                    iterator.remove();
                    result.add(entry.getKey());
                }
            }
        }
        // add to maintenance queue with new timings
        for (PeerAddress peerAddress : result) {
            addToMaintenanceQueue(peerAddress);
        }
        return result;
    }

    private void removeFromMaintenance(PeerAddress peerAddress) {
        synchronized (maintenance) {
            maintenance.remove(peerAddress);
        }
    }

    /**
     * Adds a peer to the set. If a peer reaches the bag size, the class is reported to the oversizebag. Furthermore, it
     * notifies listeners about an insert.
     * 
     * @param map
     *            The set to add the peer
     * @param remotePeer
     *            The remote peer to add
     * @param classMember
     *            The class member, which is used to report oversize.
     * @return True if the peer could be added. If the peer is already in, it returns false
     */
    private boolean insertOrUpdate(final Map<Number160, PeerAddress> map, final PeerAddress remotePeer,
            final int classMember) {
        boolean retVal;
        synchronized (map) {
            retVal = !map.containsKey(remotePeer.getID());
            map.put(remotePeer.getID(), remotePeer);
        }
        if (retVal) {
            peerCount.incrementAndGet();
            notifyInsert(remotePeer);
        } else {
            notifyUpdate(remotePeer);
        }
        return retVal;
    }

    /**
     * This method returns peers that are over sized. The peers that have been seen latest stay.
     * 
     * @return True if we could remove an oversized peer
     */
    private PeerAddress removeLatestEntryExceedingBagSize() {
        for (int classMember = Number160.BITS - 1; classMember >= 0; classMember--) {
            final Map<Number160, PeerAddress> map = peerMap.get(classMember);
            if (map.size() > bagSize) {
                long maxValue = Long.MAX_VALUE;
                PeerAddress removePeerAddress = null;
                synchronized (map) {
                    for (PeerAddress peerAddress : map.values()) {
                        final long lastSeenOline = peerMapStat.getLastSeenOnlineTime(peerAddress);
                        if (lastSeenOline < maxValue) {
                            maxValue = lastSeenOline;
                            removePeerAddress = peerAddress;
                        }
                        // TODO: idea use a score system rather than
                        // lastSeenOnline, as we might have old reliable peers.
                        if (maxValue == 0)
                            break;
                    }
                }
                if (removePeerAddress != null) {
                    return removePeerAddress;
                }
            }
        }
        return null;
    }

    private boolean shouldPeerBeRemoved(Log log) {
        return Timings.currentTimeMillis() - log.getLastOffline() <= cacheTimeout && log.getCounter() >= maxFail;
    }

    /**
     * Checks if this peer has been removed. A peer that has been removed will be stored in a cache list for a certain
     * time. This method is tread-safe
     * 
     * @param node
     *            The node to check
     * @return True if the peer has been removed recently
     */
    public boolean isPeerRemovedTemporarly(PeerAddress remotePeer) {
        Log log;
        synchronized (peerOfflineLogs) {
            log = peerOfflineLogs.get(remotePeer);
        }
        if (log != null) {
            synchronized (log) {
                if (shouldPeerBeRemoved(log))
                    return true;
                else if (Timings.currentTimeMillis() - log.getLastOffline() > cacheTimeout) {
                    // remove the peer if timeout occured
                    synchronized (peerOfflineLogs) {
                        peerOfflineLogs.remove(remotePeer);
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if a peer already existis in this map and if it does, it will update the entry becaues the peer address
     * (e.g. port) may have changed.
     * 
     * @param peerAddress
     *            The address of the peer that may have been changed.
     * @return True if we have updated the peer, false otherwise
     */
    public boolean updateExistingPeerAddress(PeerAddress peerAddress) {
        final int classMember = classMember(peerAddress.getID());
        Map<Number160, PeerAddress> tmp = peerMap.get(classMember);
        synchronized (tmp) {
            if (tmp.containsKey(peerAddress.getID())) {
                tmp.put(peerAddress.getID(), peerAddress);
                return true;
            }
        }
        return false;
    }

    public boolean contains(PeerAddress peerAddress) {
        final int classMember = classMember(peerAddress.getID());
        if (classMember == -1) {
            // -1 means we searched for ourself and we never are our neighbor
            return false;
        }
        Map<Number160, PeerAddress> tmp = peerMap.get(classMember);
        return tmp.containsKey(peerAddress.getID());
    }

    /**
     * Returns close peer from the set to a given key. This method is tread-safe. You can use the returned set as its a
     * copy of the actual PeerMap and changes in the return set do not affect PeerMap.
     * 
     * @param id
     *            The key that should be close to the keys in the map
     * @param atLeast
     *            The number we want to find at least
     * @return A navigable set with close peers first in this set.
     */
    public SortedSet<PeerAddress> closePeers(final Number160 id, final int atLeast) {
        final SortedSet<PeerAddress> set = new TreeSet<PeerAddress>(createPeerComparator(id));
        // special treatment, as we can start iterating from 0
        if (self().equals(id)) {
            for (int j = 0; set.size() < atLeast && j < Number160.BITS; j++) {
                Map<Number160, PeerAddress> tmp = peerMap.get(j);
                synchronized (tmp) {
                    set.addAll(tmp.values());
                }
            }
            return set;
        }
        final int classMember = classMember(id);
        Map<Number160, PeerAddress> tmp = peerMap.get(classMember);
        synchronized (tmp) {
            set.addAll(tmp.values());
        }
        if (set.size() >= atLeast)
            return set;
        // first go down, all the way...
        for (int i = classMember - 1; i >= 0; i--) {
            tmp = peerMap.get(i);
            synchronized (tmp) {
                set.addAll(tmp.values());
            }
        }
        if (set.size() >= atLeast)
            return set;
        // go up... these ones will be larger than our distance
        for (int i = classMember + 1; set.size() < atLeast && i < Number160.BITS; i++) {
            tmp = peerMap.get(i);
            synchronized (tmp) {
                set.addAll(tmp.values());
            }
        }
        return set;
    }

    /**
     * Returns -1 if the first remote node is closer to the key, if the second is closer, then 1 is returned. If both
     * are equal, 0 is returned
     * 
     * @param key
     *            The key to search for
     * @param nodeAddress1
     *            The remote node on the routing path to node close to key
     * @param nodeAddress2
     *            An other remote node on the routing path to node close to key
     * @return -1 if nodeAddress1 is closer to the key than nodeAddress2, otherwise 1. 0 is returned if both are equal.
     */
    public int isCloser(Number160 id, PeerAddress rn, PeerAddress rn2) {
        return isKadCloser(id, rn, rn2);
    }

    /**
     * Returns -1 if the first key is closer to the key, if the second is closer, then 1 is returned. If both are equal,
     * 0 is returned
     * 
     * @param key
     *            The key to search for
     * @param key1
     *            The first key
     * @param key2
     *            The second key
     * @return -1 if key1 is closer to key, otherwise 1. 0 is returned if both are equal.
     */
    public int isCloser(Number160 id, Number160 rn, Number160 rn2) {
        return distance(id, rn).compareTo(distance(id, rn2));
    }

    /**
     * @see PeerMap.routing.Routing#isCloser(java.math.BigInteger, PeerAddress.routing.NodeAddress,
     *      PeerAddress.routing.NodeAddress)
     * @param key
     *            The key to search for
     * @param rn2
     *            The remote node on the routing path to node close to key
     * @param rn
     *            An other remote node on the routing path to node close to key
     * @return True if rn2 is closer or has the same distance to key as rn
     */
    /**
     * Returns -1 if the first remote node is closer to the key, if the secondBITS is closer, then 1 is returned. If
     * both are equal, 0 is returned
     * 
     * @param id
     *            The id as a distance reference
     * @param rn
     *            The peer to test if closer to the id
     * @param rn2
     *            The other peer to test if closer to the id
     * @return -1 if first peer is closer, 1 otherwise, 0 if both are equal
     */
    public static int isKadCloser(Number160 id, PeerAddress rn, PeerAddress rn2) {
        return distance(id, rn.getID()).compareTo(distance(id, rn2.getID()));
    }

    /**
     * Returns the number of the class that this id belongs to
     * 
     * @param remoteID
     *            The id to test
     * @return The number of bits used in the difference.
     */
    private int classMember(Number160 remoteID) {
        return classMember(self(), remoteID);
    }

    /**
     * Returns the difference in terms of bit counts of two ids, minus 1. So two IDs with one bit difference are in the
     * class 0.
     * 
     * @param id1
     *            The first id
     * @param id2
     *            The second id
     * @return returns the bit difference and -1 if they are equal
     */
    static int classMember(Number160 id1, Number160 id2) {
        return distance(id1, id2).bitLength() - 1;
    }

    /**
     * The distance metric is the XOR metric.
     * 
     * @param id1
     *            The first id
     * @param id2
     *            The second id
     * @return The distance
     */
    static Number160 distance(Number160 id1, Number160 id2) {
        return id1.xor(id2);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("I'm node ");
        sb.append(self()).append("\n");
        for (int i = 0; i < Number160.BITS; i++) {
            final Map<Number160, PeerAddress> tmp = peerMap.get(i);
            if (tmp.size() > 0) {
                sb.append("class:").append(i).append("->\n");
                synchronized (tmp) {
                    for (PeerAddress node : tmp.values())
                        sb.append("node:").append(node).append(",");
                }
            }
        }
        return sb.toString();
    }

    public static Comparator<PeerAddress> createComparator(final Number160 id) {
        return new Comparator<PeerAddress>() {
            public int compare(PeerAddress remotePeer, PeerAddress remotePeer2) {
                return isKadCloser(id, remotePeer, remotePeer2);
            }
        };
    }

    /**
     * Creates a comparator that orders to peers according to their distance to the specified id.
     * 
     * @param id
     *            The id that defines the metric
     * @return The comparator to be used with the collection framework
     */
    public Comparator<PeerAddress> createPeerComparator(final Number160 id) {
        return createComparator(id);
    }

    public Comparator<PeerAddress> createPeerComparator() {
        return createPeerComparator(self);
    }

    /**
     * Return all addresses from the neighbor list. The collection is partially sorted.
     * 
     * @return All neighbors
     */
    public List<PeerAddress> getAll() {
        List<PeerAddress> all = new ArrayList<PeerAddress>();
        for (Map<Number160, PeerAddress> map : peerMap) {
            synchronized (map) {
                all.addAll(map.values());
            }
        }
        return all;
    }

    public void addAddressFilter(InetAddress address) {
        filteredAddresses.add(address);
    }
}