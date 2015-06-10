/*
 * This file is part of The MediaSense Platform - http://www.mediasense.se.
 *
 * The MediaSense Platform is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The MediaSense Platform is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with The MediaSense Platform.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package se.mediasense.interfacelayer;

import se.mediasense.addinlayer.AddInManager;
import se.mediasense.disseminationlayer.communication.Communication;
import se.mediasense.disseminationlayer.communication.DestinationNotReachableException;
import se.mediasense.disseminationlayer.communication.MediaSenseHost;
import se.mediasense.disseminationlayer.disseminationcore.DisseminationCore;
import se.mediasense.disseminationlayer.lookupservice.LookupService;
import se.mediasense.distribution.Deleter;
import se.mediasense.distribution.Getter;
import se.mediasense.distribution.Registrator;
import se.mediasense.distribution.Resolver;
import se.mediasense.distribution.UCI;
import se.mediasense.distribution.Updater;
import se.mediasense.messages.MediaSenseListener;
import se.mediasense.messages.MediaSenseMessage;
import se.mediasense.messages.NotifyMessage;
import se.mediasense.messages.SetMessage;
import se.mediasense.util.MediaSenseConstants;

/**
 *
 * The MediaSense platform itself, which exposes all functionality towards the
 * application developers. All activity using the MediaSense platform must
 * orginate here. <p>The constructor
 * {@link #MediaSensePlatform(MediaSenseApplication application) MediaSensePlatform}
 * requires that a default {@link se.interfacelayer.MediaSenseApplication}
 * must be specified as an argument to the constructor <p>Where no other
 * {@link se.mediasense.messages.MediaSenseListener} is specified for the a
 * specfic {@link se.mediasense.messages.MediaSenseMessage} then the messages
 * are sent to the defualt listener for processing. <p>The platform must be
 * {@link #MediaSensePlatform(MediaSenseApplication application) Constructed} and
 * {@link #init(String bootip, int bootport, int localport) Initialized} before
 * becoming accessible.
 *
 */
public final class MediaSensePlatform
{

    private DisseminationCore disseminationcore = MediaSenseConstants.DISSEMINATIONCORE;
    private AddInManager addInManager = null;
    private MediaSenseApplication application;
    private Communication communication = MediaSenseConstants.COMMUNICATION;
    private LookupService lookupservice = MediaSenseConstants.LOOKUPSERVICE;

   
    /**
     * This is the only constructor for the MediaSensePlatform API. The
     * constructor, no initialization is done, use <b>init</b> to start the
     * platform and establish network connectivity.
     *
     * @param app This is the
     * {@link se.interfacelayer.MediaSenseApplication} for all messages. It is
     * used when other message handlers are registered. Manage message specific
     * handlers with
     * {@link #registerListener(String msgtype, MediaSenseListener listener) registerListener}
     * and
     * {@link #removeListener(String msgtype, MediaSenseListener listener) removeListener}
     */
    public MediaSensePlatform(MediaSenseApplication app)
    {
    	MediaSenseConstants.loadProps();
        addInManager = new AddInManager(this);

        application = app;

    }

    /**
     * De-registers and shutsdown the entire platform along with network
     * connectivity. No applications are executable after this method has been
     * called. All currently loaded addins are also unloaded.
     */
    public void shutdown()
    {
        addInManager.unloadAllAddIns();

        communication.shutdown();

        lookupservice.shutdown();

    }

    /**
     * Initializes the Distributed MediaSense platform. Must be called before
     * using the any other functions.
     *
     * @param boostrapaddress This is the address of the bootstrap node
     * @param bootstrapport This is the listening port of the bootstrap node
     * @param localport This is the listening port of the node being created on this device
     * @return true if successfully started
     */
    public boolean init(String boostrapaddress, int bootstrapport, int localport)
    {

        MediaSenseConstants.DISSEMINATIONCORE.registerListener(DisseminationCore.DEFAULT_LISTENER, (MediaSenseListener) application);
        MediaSenseConstants.COMMUNICATION.init(boostrapaddress, bootstrapport, localport );
        return MediaSenseConstants.COMMUNICATION.isInitialized();

    }

    /**
     * Initializes the Distributed MediaSense platform using the default boostrap and port. 
     * The current bootstrap address can be located on http://www.mediasense.se
     * @return true if successfully started
     */
    public boolean init()
    {

        return init("local ip e.g. 192.168.0.1", 2000, 8000); //the ip address should be changed to local ip for local bootstrapping
		//In order to use the MediaSense's bootstrap ip,contact us!

    }
    /**
     * Initializes the Distributed MediaSense platform using the default boostrap and port. 
     * The current bootstrap address can be located on http://www.mediasense.se
     * @param localport This is the listening port of the node being created on this device
     * @return true if successfully started
     */
       public boolean init(int localport)
    {

            return init("local ip e.g. 192.168.0.1", 2000, localport); //the ip address should be changed to local ip for local bootstrapping
		   //In order to use the MediaSense's bootstrap ip,contact us!

    } 
    /**
     * Returns the dissemination core, which is used to call primitive functions
     * directly
     *
     * @return the running dissemination core
     */
    public DisseminationCore getDisseminationCore()
    {

        return disseminationcore;
    }

    /**
     * Returns the add-in manager, which handles loading and unloading of
     * add-ins.
     *
     * @return the add-in manager
     */
    public AddInManager getAddInManager()
    {

        return addInManager;
    }

    /**
     * Checks whether the MediaSensePlatform has been successfully initialized,
     * started, and is currently running
     *
     * @return true if the platform is running
     */
    public boolean isInitalized()
    {

        return MediaSenseConstants.COMMUNICATION.isInitialized();

    }

    /**
     * The RESOLVE primitive action, which resolves an UCI to the node which
     * owns the resource identified by this UCI <p>This call is asynchronous or synchronous.
     * @param uci the UCI to be resolved
     * @return the resolver object
     */
    public Resolver resolveUCI(String uci)
    {

        return new Resolver(uci);

    }

    

    /**
     * The REGISTER primitive action, which registers an UCI which can later be
     * resolved and found by other MediaSenseHosts <p>This call is asynchronous or synchronous.
     * <p>This method assumes that the resources is owned by the local
     * {@link se.mediasense.disseminationlayer.communication.MediaSenseHost}
     *
     * @param uci - the UCI to be registered
     * @return the registrator object
     */
    public Registrator registerUCI(String uci)
    {
    	return new Registrator(uci);
    }
    
    
    /**
     * The DELETE action, which deletes an UCI <p>This call is asynchronous or synchronous.
     * <p>This method assumes that the resources is owned by the local
     * {@link se.mediasense.disseminationlayer.communication.MediaSenseHost}
     *
     * @param uci - the UCI to be registered
     * @return the deleter object
     */  
    public Deleter delete(UCI uci)
    {
        
    	return new Deleter(uci);

    }
    
    
    
    /**
     * The Update action, which updates an UCI which can later be
     * resolved and found by other MediaSenseHosts <p>This call is asynchronous or synchronous.
     * <p>This method assumes that the resources is owned by the local
     * {@link se.mediasense.disseminationlayer.communication.MediaSenseHost}
     *
     * @param uci - the UCI to be registered
     * @return the updater object
     */ 
    
    public Updater update(String uci)
    {
        
    	return new Updater(uci);

    }


    /**
     * The GET primitive action, which fetches the value from another entity.
     * <p>This call is asynchronous or synchronous. 
     * @param uci the UCI to be fetched
     * @return the getter object
     */ 
    public Getter get(String uci)
    {
       Getter g = new Getter(uci);
       
       return g;

    }
    
    /**
     * The GET primitive action, which fetches the value from another entity.
     * <p>This call is asynchronous or synchronous. 
     * @param uci the UCI to be fetched
     * @return the getter object
     */ 
    public Getter get(UCI uci)
    {
       Getter g = new Getter(uci.getUciAsString());
       
       return g;

    }

    /**
     * The SET primitive action, which fetches the value from another entity.
     * <p>This call is asynchronous or synchronous. 
     * @param uci the UCI to be set
     * @param value the value to be set
     */
    public void set(UCI uci, java.io.Serializable value) throws DestinationNotReachableException
    {
        
    	
    	MediaSenseMessage m = new SetMessage(uci, value, uci.getOwner(), this.getLocalHost());

        disseminationcore.Dispatch(m);

    }


    /**
     * The NOTIFY primitive action, which sends a value back to a previously
     * asking entity
     * <p>This call is asynchronous or synchronous. 
     * @param uci the UCI of the {@link java.io.Serilizable}value
     * @param value the {@link java.io.Serilizable} that should be sent in
     * response the {@link se.mediasense.messages.GetMessage}
     */
    public void notify(UCI uci, java.io.Serializable value, MediaSenseHost destination) throws DestinationNotReachableException
    {

        MediaSenseMessage m = new NotifyMessage(uci, value, destination, MediaSenseConstants.LOCALHOST);

        disseminationcore.Dispatch(m);
    }

    

    /**
     * Registers an event listener with the API. <p>This listener must implement
     * the {@link se.mediasense.messages.MediaSenseListener} interface <p>This
     * overrides the default listener for this type of message. If a listener is
     * specified, the default listener will no longer receive messages for the
     * specified message type.
     *
     * @param msgtype the message type which the value should be sent to
     * @param listener the {@link se.mediasense.messages.MediaSenseListener}
     * that should be notified of the messages
     */
    public void registerListener(String msgtype, MediaSenseListener listener)
    {

        disseminationcore.registerListener(msgtype, listener);

    }

    /**
     * Removes or de-registers an event listener with the API. <p>This listener
     * must implement the {@link se.mediasense.messages.MediaSenseListener}
     * interface <p>If no listener is specified for this type of
     * {@link se.mediasense.messages.MediaSenseMessage} then the event defaults
     * back to the default listener specified in
     * {@link #MediaSensePlatform(MediaSenseListener listener) MediaSensePlatform}
     *
     * @param msgtype the message type which the value should be sent to
     * @param listener the {@link se.mediasense.messages.MediaSenseListener}
     * that should be removed for this type message
     */
    public void removeListener(String msgtype, MediaSenseListener listener)
    {

        disseminationcore.removeListener(msgtype, listener);

    }

    /**
     * Sends a {@link se.mediasense.messages.MediaSenseMessage} using the
     * generic messaging interface. <p>The message is dispatched in accordance
     * to the underlaying communication protocol to a
     * {@link se.mediasense.disseminationlayer.communication.MediaSenseHost} and
     * is received in the first instance by the
     * {@link se.mediasense.messages.MediaSenseListener} on the remote
     * {@link se.mediasense.disseminationlayer.communication.MediaSenseHost}
     * registered for this message type. <p>If no
     * {@link se.mediasense.messages.MediaSenseListener} is specified for this
     * type of message then it is delivered to the default
     * {@link se.mediasense.messages.MediaSenseListener} specified in
     * {@link #MediaSensePlatform(MediaSenseListener listener) MediaSensePlatform}
     *
     * @param msg the message to be delivered
     */
    public void sendMessage(MediaSenseMessage msg) throws DestinationNotReachableException
    {

        disseminationcore.Dispatch(msg);

    }

    /**
     * Returns the localhost This is implementation specific, however for most
     * cases, the getHostID is sufficient identification for the host. However,
     * the entire object must be used for transactions.
     *
     * @return
     * {@link se.mediasense.disseminationlayer.communication.MediaSenseHost}
     */
    public MediaSenseHost getLocalHost()
    {
        return MediaSenseConstants.LOCALHOST;
    }
    
    public static void main(String [] args)
    {
    	
    }
}
