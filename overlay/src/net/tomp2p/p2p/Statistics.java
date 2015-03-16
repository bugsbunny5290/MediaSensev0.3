/*
 * Copyright 2012 Thomas Bocek
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
import java.util.List;
import java.util.Map;

import net.tomp2p.peers.Number160;
import net.tomp2p.peers.PeerAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Statistics {
    final private static Logger logger = LoggerFactory.getLogger(Statistics.class);

    private final static double MAX = Math.pow(2, Number160.BITS);

    private double estimatedNumberOfPeers = 1;

    private double avgGap = MAX / 2;

    //
    private final List<Map<Number160, PeerAddress>> peerMap;

    // private final Number160 remotePeer;
    private final int maxSize;

    private final int bagSize;

    private int currentSize;

    // no need to be volatile, since they are synced
    private long tcpCreationCount = 0;

    private long udpCreationCount = 0;

    private long tcpCount = 0;

    private long udpCount = 0;

    public Statistics(List<Map<Number160, PeerAddress>> peerMap, Number160 remotePeer, int maxSize, int bagSize) {
        this.peerMap = peerMap;
        // this.remotePeer = remotePeer;
        this.maxSize = maxSize;
        this.bagSize = bagSize;
    }

    // TODO: with increasing number of peers, the diff gets lower and lower
    public void triggerStatUpdate(boolean insert, int currentSize) {
        this.currentSize = currentSize;
    }

    public double getEstimatedNumberOfNodes() {
        // we know it exactly
        if (currentSize < maxSize) {
            estimatedNumberOfPeers = currentSize;
            return estimatedNumberOfPeers;
        }
        // otherwise we know we are full!
        double gap = 0D;
        int gapCount = 0;
        int oldNumPeers = 0;
        for (int i = Number160.BITS - 1; i >= 0; i--) {
            Map<Number160, PeerAddress> peers = peerMap.get(i);
            int numPeers = peers.size();
            // System.out.print(numPeers);
            // System.out.print(",");
            if (numPeers > 0 && (numPeers < bagSize || numPeers < oldNumPeers)) {
                double currentGap = Math.pow(2, i) / numPeers;
                // System.out.print("gap("+i+"):"+currentGap+";");
                gap += currentGap * numPeers;
                gapCount += numPeers;
            } else {
                // System.out.print("ignoring "+i+";");
            }
            oldNumPeers = numPeers;
        }
        // System.out.print("\n");
        avgGap = gap / gapCount;
        estimatedNumberOfPeers = (MAX / avgGap);
        return estimatedNumberOfPeers;
    }

    public double getAvgGap() {
        return avgGap;
    }

    public static void tooClose(Collection<PeerAddress> collection) {

    }

    public void incrementTCPChannelCreation() {
        synchronized (this) {
            tcpCreationCount++;
            tcpCount++;
        }
    }

    public void incrementUDPChannelCreation() {
        synchronized (this) {
            udpCreationCount++;
            udpCount++;
        }
    }

    public void decrementTCPChannelCreation() {
        synchronized (this) {
            tcpCount--;
            if (logger.isDebugEnabled()) {
                logger.debug("TCP channel count is " + tcpCount);
            }
        }
    }

    public void decrementUDPChannelCreation() {
        synchronized (this) {
            udpCount--;
            if (logger.isDebugEnabled()) {
                logger.debug("UDP channel count is " + udpCount);
            }
        }
    }

    public long getTCPChannelCreationCount() {
        synchronized (this) {
            return tcpCreationCount;
        }
    }

    public long getUDPChannelCreationCount() {
        synchronized (this) {
            return udpCreationCount;
        }
    }

    public long getTCPChannelCount() {
        synchronized (this) {
            return tcpCount;
        }
    }

    public long getUDPChannelCount() {
        synchronized (this) {
            return udpCount;
        }
    }
}
