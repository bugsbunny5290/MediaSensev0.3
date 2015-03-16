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
package net.tomp2p.connection;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.tomp2p.message.Message;
import net.tomp2p.message.Message.Command;
import net.tomp2p.message.Message.Type;
import net.tomp2p.p2p.ConnectionConfiguration;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.peers.PeerMap;
import net.tomp2p.rpc.ReplyHandler;
import net.tomp2p.utils.Timings;

import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.socket.DatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used to deliver incoming REQUEST messages to their specific handlers. You can
 * register handlers using the {@link registerIoHandler} function.
 * <p>
 * You probably want to add an instance of this class to the end of a pipeline
 * to be able to receive messages. This class is able to cover several channels
 * but only one P2P network!
 * </p>
 * 
 * @author Thomas Bocek
 */
@Sharable
public class DispatcherReply extends SimpleChannelHandler {
    final private static Logger logger = LoggerFactory.getLogger(DispatcherReply.class);

    // Stores all registered handlers, DIY copy on write map
    private volatile Map<Number160, Map<Command, ReplyHandler>> listenersRequest = null;

    private volatile Set<ReplyHandler> handlers = null;

    /**
     * The p2p ID the dispatcher is looking for. Messages with different ID's
     * will be discarded.
     */
    final private int p2pID;

    /**
     * Stores the first address which is registered with this dispatcher. Used
     * for messages which do not specify a receiver (they will thus be handled
     * like the first receiver you register).
     */
    final private PeerBean peerBean;

    final private ConnectionConfiguration configuration;

    final private ChannelGroup channelGroup;

    final private PeerMap peerMap;

    /**
     * Constructor
     * 
     * @param p2pID
     *            the p2p ID the dispatcher is looking for in messages
     * @param routing
     */
    public DispatcherReply(int p2pID, PeerBean peerBean, ConnectionConfiguration configuration,
            ChannelGroup channelGroup, PeerMap peerMap) {
        this.p2pID = p2pID;
        // its ok not to have the right IP and port, since the dispatcher only
        // replies to requests, and the sender IP will be handled by layer 4.
        this.peerBean = peerBean;
        this.configuration = configuration;
        this.channelGroup = channelGroup;
        this.peerMap = peerMap;
    }

    /**
     * Registers a handler with this dispatcher. Future received messages
     * adhering to the given parameters will be forwarded to that handler. Note
     * that the dispatcher only handles REQUEST messages. This method is
     * thread-safe, and uses copy on write as its expected to run this only
     * during initialization.
     * 
     * @param sender
     *            Specifies the receiver the dispatcher filters for. This allows
     *            to use one dispatcher for several interfaces or even nodes.
     *            TODO: why call it sender here if it really acts as receiver?
     * @param name
     *            The command of the {@link Message} the given handler
     *            processes. All messages having that command will be forwarded
     *            to the given handler.<br />
     *            <b>Note:</b> If you register multiple handlers with the same
     *            command, only the last registered handler will receive these
     *            messages!
     * @param ioHandler
     *            the handler which should process the given type of messages
     */
    public void registerIoHandler(Number160 localPeerId, ReplyHandler ioHandler, Command... names) {
        synchronized (this) {
            Set<ReplyHandler> copy1 = handlers == null ? new HashSet<ReplyHandler>() : new HashSet<ReplyHandler>(
                    handlers);
            copy1.add(ioHandler);
            handlers = Collections.unmodifiableSet(copy1);
            //
            Map<Number160, Map<Command, ReplyHandler>> copy2 = listenersRequest == null ? new HashMap<Number160, Map<Command, ReplyHandler>>()
                    : new HashMap<Number160, Map<Command, ReplyHandler>>(listenersRequest);
            //
            Map<Command, ReplyHandler> types = copy2.get(localPeerId);
            if (types == null) {
                types = new HashMap<Command, ReplyHandler>();
                copy2.put(localPeerId, types);
            }
            for (Command name : names)
                types.put(name, ioHandler);
            //
            listenersRequest = Collections.unmodifiableMap(copy2);
        }
    }

    /**
     * If we shutdown, we remove the handlers. This means that a server may
     * respond that the handler is unknown.
     * 
     * @param id
     *            The Id of the peer to remove the handlers.
     */
    public void removeIoHandler(Number160 localPeerId) {
        synchronized (this) {
            if (listenersRequest != null) {
                Map<Number160, Map<Command, ReplyHandler>> copy2 = new HashMap<Number160, Map<Command, ReplyHandler>>(
                        listenersRequest);
                Set<ReplyHandler> copy1 = new HashSet<ReplyHandler>(handlers);
                Map<Command, ReplyHandler> types = copy2.remove(localPeerId);
                if (types != null) {
                    copy1.removeAll(types.values());
                }
                handlers = Collections.unmodifiableSet(copy1);
                listenersRequest = Collections.unmodifiableMap(copy2);
            }
        }
    }

    @Override
    public void channelOpen(final ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        channelGroup.add(ctx.getChannel());
        ctx.sendUpstream(e);
    }

    /**
     * Called if we get an exception
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
        if (logger.isWarnEnabled() && e.getCause() != null
                && !e.getCause().toString().startsWith("java.io.IOException")) {
            logger.warn("error in dispatcher request" + e.toString());
            // e.getCause().printStackTrace();
        }
        ctx.sendUpstream(e);
    }

    /**
     * Called if we get a message
     */
    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        // We only want to receive messages
        if (!(e.getMessage() instanceof Message)) {
            logger.error("Message received, but not of type Message: " + e.getMessage());
            close(ctx);
            return;
        }
        final Message message = (Message) e.getMessage();
        if (logger.isDebugEnabled()) {
            logger.debug("received request " + message);
        }
        // We don't want to receive messages from other p2p networks
        if (message.getVersion() != p2pID) {
            logger.error("Wrong version. We are looking for " + p2pID + " but we got " + message.getVersion()
                    + ", received: " + message);
            close(ctx);
            peerMap.peerOffline(message.getSender(), true);
            return;
        }
        int timeout = (ctx.getChannel() instanceof DatagramChannel) ? configuration.getIdleUDPMillis() : configuration
                .getIdleTCPMillis();
        // no need to reply, we are late anyway
        if (Timings.currentTimeMillis() > message.getFinished() + timeout) {
            logger.info("We are very busy and cannto reply in time (timeout=" + timeout + "), so we drop:" + message);
            close(ctx);
            return;
        }
        Message responseMessage = null;
        // Look for a handler for the received message
        final ReplyHandler myHandler = getAssociatedHandler(message);
        if (myHandler != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("about to respond to " + message);
            }
            responseMessage = myHandler.forwardMessage(message);
            if (responseMessage == null) {
                logger.warn("Repsonse message was null, probaly a custom handler failed " + message);
                message.setRecipient(message.getSender()).setSender(peerBean.getServerPeerAddress())
                        .setType(Type.EXCEPTION);
                response(ctx, e, message, false);
            } else if (responseMessage == message) {
                if (logger.isDebugEnabled()) {
                    logger.debug("The reply handler was a fire-and-forget handler, we don't send any message back! "
                            + message);
                }
                close(ctx);
            } else {
                response(ctx, e, responseMessage, message.isKeepAlive());
            }
        } else {
            logger.warn("No handler found for " + message + ". Probably we have shutdown this peer.");
            message.setRecipient(message.getSender()).setSender(peerBean.getServerPeerAddress())
                    .setType(Type.UNKNOWN_ID);
            response(ctx, e, message, false);
        }
    }

    /**
     * Respond within a session. Keep the connection open if we are asked to do
     * so. Connection is only kept alive for TCP data.
     * 
     * @param ctx
     * @param e
     * @param response
     * @param isKeepAlive
     */
    private void response(final ChannelHandlerContext ctx, MessageEvent e, final Message response, boolean isKeepAlive) {

        if (ctx.getChannel() instanceof DatagramChannel) {
            // check if channel is still open. If its not, then do not send
            // anything because
            // this will cause an exception that will be logged.
            if (!ctx.getChannel().isOpen()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("channel is not open, do not reply " + response);
                }
                return;
            }
            if (logger.isDebugEnabled())
                logger.debug("reply UDP message " + response);
            // no need to close a local channel, as we do not open a local
            // channel for UDP during a reply. This is our server socket!
            e.getChannel().write(response, e.getRemoteAddress());
            // TODO: if we set a timeout in ConnectionHandler, we need to cancel
            // the timeout here.
        } else {
            // check if channel is still open. If its not, then do not send
            // anything because
            // this will cause an exception that will be logged.
            if (!ctx.getChannel().isConnected()) {
                if (logger.isDebugEnabled()) {
                    logger.debug("channel is not open, do not reply " + response);
                }
                return;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("reply TCP message " + response + " to " + ctx.getChannel().getRemoteAddress());
            }
            ChannelFuture cf = ctx.getChannel().write(response);
            if (!isKeepAlive) {
                cf.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        future.getChannel().close();
                    }
                });
            }
        }
    }

    /**
     * Close the channel if its not UDP.
     * 
     * @param ctx
     *            The channel context
     */
    private static void close(ChannelHandlerContext ctx) {
        if (!(ctx.getChannel() instanceof DatagramChannel)) {
            ctx.getChannel().close();
        }
        // TODO: if we set a timeout in ConnectionHandler, we need to cancel the
        // timeout here.
    }

    /**
     * Checks if we have a handler for the given message
     * 
     * @param message
     *            the message a handler should be found for
     * @return the handler for the given message, null if none has been
     *         registered for that message.
     */
    private ReplyHandler getAssociatedHandler(Message message) {
        if (message == null || !(message.isRequest()))
            return null;
        PeerAddress recipient = message.getRecipient();
        // Search for handler
        if (recipient.getID().isZero() && message.getCommand() == Command.PING)
            return searchHandler(peerBean.getServerPeerAddress().getID(), Command.PING);
        else
            return searchHandler(recipient.getID(), message.getCommand());
    }

    /**
     * Looks for a registered handler according to the given parameters
     * 
     * @param recipientID
     *            The recipient of the message
     * @param command
     *            The type of the message to be filtered
     * @return the handler for the given message or null if none has been found
     */
    private ReplyHandler searchHandler(Number160 recipientID, Command command) {
        Map<Number160, Map<Command, ReplyHandler>> listenersRequest2 = listenersRequest;
        if (listenersRequest2 == null)
            return null;
        Map<Command, ReplyHandler> types = listenersRequest2.get(recipientID);
        if (types != null && types.containsKey(command))
            return types.get(command);
        else {
            // not registered
            if (logger.isDebugEnabled()) {
                logger.debug("Handler not found for type " + command + ", we are looking for the server with ID "
                        + recipientID);
            }
            return null;
        }
    }
}