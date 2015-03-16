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
package net.tomp2p.storage;

import net.tomp2p.peers.PeerAddress;

public class TrackerData implements Comparable<TrackerData> {
    final private PeerAddress peerAddress;

    final private PeerAddress referrer;

    final private byte[] attachement;

    final private int offset;

    final private int length;

    final private boolean couldProvideMoreData;

    public TrackerData(PeerAddress peerAddress, PeerAddress referrer, byte[] attachement, int offset, int length) {
        this(peerAddress, referrer, attachement, offset, length, false);
    }

    public TrackerData(PeerAddress peerAddress, PeerAddress referrer, byte[] attachement, int offset, int length,
            boolean couldProvideMoreData) {
        this.peerAddress = peerAddress;
        this.referrer = referrer;
        this.attachement = attachement;
        this.offset = offset;
        this.length = length;
        this.couldProvideMoreData = couldProvideMoreData;
    }

    public PeerAddress getPeerAddress() {
        return peerAddress;
    }

    public PeerAddress getReferrer() {
        return referrer;
    }

    public byte[] getAttachement() {
        return attachement;
    }

    public int getOffset() {
        return offset;
    }

    public int getLength() {
        return length;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof TrackerData)) {
            return false;
        }
        TrackerData trackerData = (TrackerData) obj;
        return trackerData.getPeerAddress().getID().equals(getPeerAddress().getID());
    }

    @Override
    public int hashCode() {
        return getPeerAddress().getID().hashCode();
    }

    @Override
    public int compareTo(TrackerData o) {
        return getPeerAddress().getID().compareTo(o.getPeerAddress().getID());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("p:").append(peerAddress).append(",l:").append(length);
        return sb.toString();
    }

    public boolean couldProvideMoreData() {
        return couldProvideMoreData;
    }
}
