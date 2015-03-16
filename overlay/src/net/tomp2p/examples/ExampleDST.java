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

package net.tomp2p.examples;

import java.io.IOException;
import java.io.Serializable;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.tomp2p.futures.FutureDHT;
import net.tomp2p.p2p.Peer;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.Number480;
import net.tomp2p.storage.Data;
import net.tomp2p.storage.StorageMemory;

/**
 * Example how to do range queries.
 * 
 * @author Thomas Bocek
 * 
 */
public final class ExampleDST {

    /**
     * Empty constructor.
     */
    private ExampleDST() {
    }

    /**
     * Start the examples.
     * 
     * @param args
     *            Empty
     * @throws Exception .
     */
    public static void main(final String[] args) throws Exception {
        final int peerNr = 100;
        final int port = 4001;
        Peer[] peers = null;
        try {
            peers = ExampleUtils.createAndAttachNodes(peerNr, port);
            ExampleUtils.bootstrap(peers);

            exampleDST(peers);
        } finally {
            // 0 is the master
            if (peers != null && peers[0] != null) {
                peers[0].shutdown();
            }
        }
    }

    /**
     * Adds a custom storage class that has a limited storage size according to the DST size.
     * 
     * @param peers
     *            All the peers
     * @param max
     *            The max. number of elements per node.
     */
    private static void setupStorage(final Peer[] peers, final int max) {
        for (Peer peer : peers) {
            StorageMemory sm = new StorageMemory() {
                @Override
                public PutStatus put(final Number160 locationKey, final Number160 domainKey,
                        final Number160 contentKey, final Data newData, final PublicKey publicKey,
                        final boolean putIfAbsent, final boolean domainProtection) {
                    Map<Number480, Data> map = subMap(locationKey, domainKey, Number160.ZERO, Number160.MAX_VALUE);
                    if (map.size() < max) {
                        return super.put(locationKey, domainKey, contentKey, newData, publicKey, putIfAbsent,
                                domainProtection);
                    } else {
                        return PutStatus.FAILED;
                    }
                }

                @Override
                public SortedMap<Number480, Data> get(final Number160 locationKey, final Number160 domainKey,
                        final Number160 fromContentKey, final Number160 toContentKey) {
                    SortedMap<Number480, Data> tmp = super.get(locationKey, domainKey, fromContentKey, toContentKey);
                    return wrap(tmp);
                }

                /**
                 * We need to tell if our bag is full and if the peer should contact other peers.
                 * 
                 * @param tmp
                 *            The original data
                 * @return The enriched data with a boolean flag
                 */
                private SortedMap<Number480, Data> wrap(final SortedMap<Number480, Data> tmp) {
                    SortedMap<Number480, Data> retVal = new TreeMap<Number480, Data>();
                    for (Map.Entry<Number480, Data> entry : tmp.entrySet()) {
                        try {
                            String data = (String) entry.getValue().getObject();
                            retVal.put(entry.getKey(), new Data(new StringBoolean(tmp.size() < max, data)));
                        } catch (ClassNotFoundException e) {
                            e.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    return retVal;
                }
            };
            peer.getPeerBean().setStorage(sm);
        }
    }

    /**
     * Store and retrieve items for a DST.
     * 
     * @param peers
     *            All the peers
     * @throws IOException .
     * @throws ClassNotFoundException .
     */
    private static void exampleDST(final Peer[] peers) throws IOException, ClassNotFoundException {
        final int n = 2;
        final int m = 8; // 1024*1024; // 2 items per peer
        final int peer16 = 16;
        final int peer55 = 55;
        //
        final int index1 = 1;
        final int index2 = 2;
        final int index3 = 3;
        final int index5 = 5;
        setupStorage(peers, n);
        int width = m / n;
        int height = (int) log2(width);
        Interval inter = new Interval(1, m);
        putDST(peers[peer16], index1, "test", inter, height);
        putDST(peers[peer16], index2, "hello", inter, height);
        putDST(peers[peer16], index3, "world", inter, height);
        putDST(peers[peer16], index5, "sys", inter, height);
        // search [1..5]
        Collection<Interval> inters = splitSegment(index1, index5, index3, m, height);
        Collection<String> result = getDST(peers[peer55], inters, n);
        System.out.println("got from range query raw " + result);
        System.out.println("got from range query unique " + new HashSet<String>(result));
    }

    /**
     * Returns a list of strings from a particular range.
     * 
     * @param peer
     *            The peer that searches for a range
     * @param inters
     *            The intervals to search for
     * @param bagSize
     *            The max. number of items per node.
     * @return A list of strings from the given range.
     * @throws ClassNotFoundException .
     * @throws IOException .
     */
    private static Collection<String> getDST(final Peer peer, final Collection<Interval> inters, final int bagSize)
            throws ClassNotFoundException, IOException {
        Collection<String> retVal = new ArrayList<String>();
        AtomicInteger dhtCounter = new AtomicInteger();
        getDSTRec(peer, inters, retVal, new HashSet<String>(), dhtCounter, bagSize);
        System.out.println("for get we used " + dhtCounter + " DHT calls");
        return retVal;
    }

    /**
     * Recursive method for searching all items for a range. We need to do recursion because when we find out that a
     * node is full, we need to go further.
     * 
     * @param peer
     *            The peer that searches for a range
     * @param inters
     *            The interval to search for
     * @param result
     *            The list where the results are stored
     * @param already
     *            Already queried intervals
     * @param dhtCounter
     *            The counter for the DHT calls
     * @param bagSize
     *            The size of the items per node
     * @throws ClassNotFoundException .
     * @throws IOException .
     */
    private static void getDSTRec(final Peer peer, final Collection<Interval> inters, final Collection<String> result,
            final Collection<String> already, final AtomicInteger dhtCounter, final int bagSize)
            throws ClassNotFoundException, IOException {
        for (Interval inter2 : inters) {
            // we don't query the same thing again
            if (already.contains(inter2.toString())) {
                continue;
            }
            Number160 key = Number160.createHash(inter2.toString());
            already.add(inter2.toString());
            // get the interval
            System.out.println("get for " + inter2);
            FutureDHT futureDHT = peer.get(key).setAll().start();
            futureDHT.awaitUninterruptibly();
            dhtCounter.incrementAndGet();
            for (Map.Entry<Number160, Data> entry : futureDHT.getDataMap().entrySet()) {
                // with each result we get a flag if we should query the children (this should be returned in the
                // future, e.g., partially_ok)
                StringBoolean stringBoolean = (StringBoolean) entry.getValue().getObject();
                result.add(stringBoolean.string);
                if (!stringBoolean.bool && inter2.size() > bagSize) {
                    // we need to query our children
                    getDSTRec(peer, inter2.split(), result, already, dhtCounter, bagSize);
                }
            }
        }
    }

    /**
     * Stores values in the given intervals.
     * 
     * @param peer
     *            The peer that stores a value which is searchable using ranges.
     * @param index
     *            Under which key to store a value
     * @param word
     *            The word to store
     * @param interval
     *            The interval that is relevant
     * @param height
     *            The hierarchy level
     * @throws IOException .
     */
    private static void putDST(final Peer peer, final int index, final String word, final Interval interval,
            final int height) throws IOException {
        Interval inter = interval;
        for (int i = 0; i <= height; i++) {
            Number160 key = Number160.createHash(inter.toString());
            FutureDHT futureDHT = peer.put(key).setData(new Number160(index), new Data(word)).start();
            futureDHT.awaitUninterruptibly();
            System.out.println("stored " + word + " in " + inter + " status: " + futureDHT.getAvgStoredKeys());
            inter = inter.split(index);
        }
        System.out.println("for DHT.put() we used " + (height + 1) + " DHT calls");
    }

    /**
     * @param num
     *            number
     * @return Logarithum dualis
     */
    private static double log2(final double num) {
        return (Math.log(num) / Math.log(2d));
    }

    /**
     * Splits a range into intervals.
     * 
     * @param s
     *            Range from
     * @param t
     *            Range to
     * @param lower
     *            Lower bound
     * @param upper
     *            Upper bound
     * @param maxDepth
     *            Hierarchy level
     * @return The intervals for a segment
     */
    private static Collection<Interval> splitSegment(final int s, final int t, final int lower, final int upper,
            final int maxDepth) {
        Collection<Interval> retVal = new ArrayList<Interval>();
        splitSegment(s, t, lower, upper, maxDepth, retVal);
        return retVal;
    }

    /**
     * Splits a segment into intervals.
     * 
     * @param s
     *            Range from
     * @param t
     *            Range to
     * @param lower
     *            Lower bound
     * @param upper
     *            Upper bound
     * @param maxDepth
     *            Hierarchy level
     * @param retVal
     *            Result collection
     */
    private static void splitSegment(final int s, final int t, final int lower, final int upper, final int maxDepth,
            final Collection<Interval> retVal) {
        if (s <= lower && upper <= t || maxDepth == 0) {
            retVal.add(new Interval(lower, upper));
            return;
        }
        int mid = (lower + upper) / 2;
        if (s <= mid) {
            splitSegment(s, t, lower, mid, maxDepth - 1, retVal);
        }
        if (t > mid) {
            splitSegment(s, t, mid + 1, upper, maxDepth - 1, retVal);
        }
    }

    /**
     * A class that stores a string together with a boolean.
     * 
     * @author Thomas Bocek
     * 
     */
    private static final class StringBoolean implements Serializable {
        private static final long serialVersionUID = -3947493823227587011L;

        private final Boolean bool;

        private final String string;

        /**
         * Constructor.
         * @param bool A boolean value
         * @param string A string value
         */
        private StringBoolean(final boolean bool, final String string) {
            this.bool = bool;
            this.string = string;
        }
    }

    /**
     * A helper class that stores the interval.
     * 
     * @author Thomas Bocek
     *
     */
    private static final class Interval {
        private final int from;

        private final int to;

        /**
         * @param from Interval from
         * @param to Interval to
         */
        private Interval(final int from, final int to) {
            if (from > to) {
                throw new IllegalArgumentException("from cannot be greater than to");
            }
            this.from = from;
            this.to = to;
        }

        @Override
        public String toString() {
            return "interv. [" + from + ".." + to + "]";
        }

        /**
         * @return Split the interval into to parts
         */
        private Collection<Interval> split() {
            int mid = ((to - from) + 1) / 2;
            int tok = from + mid;
            Collection<Interval> retVal = new ArrayList<Interval>();
            retVal.add(new Interval(from, tok - 1));
            retVal.add(new Interval(tok, to));
            return retVal;
        }

        /**
         * @param nr From where to split
         * @return split the interval at the given position
         */
        private Interval split(final int nr) {
            int mid = ((to - from) + 1) / 2;
            int tok = from + mid;
            if (nr < tok) {
                // left interval
                return new Interval(from, tok - 1);
            } else {
                // right interval
                return new Interval(tok, to);
            }
        }

        /**
         * @return The size of the interval.
         */
        private int size() {
            return (to - from) + 1;
        }
    }
}
