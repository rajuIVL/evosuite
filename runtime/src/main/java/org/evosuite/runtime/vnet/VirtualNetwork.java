package org.evosuite.runtime.vnet;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

import org.evosuite.runtime.mock.java.net.MockInetAddress;
import org.evosuite.runtime.mock.java.net.MockURL;


/**
 * Singleton class used to simulate a virtual network.
 * This is useful to test classes that use UDP/TCP connections
 * 
 * @author arcuri
 *
 */
public class VirtualNetwork {

	public enum ConnectionType {UDP,TCP};

	/**
	 * Singleton instance
	 */
	private static final VirtualNetwork instance = new VirtualNetwork();

	/**
	 * When we simulate a remote incoming connection, we still need a remote port.
	 * Note: in theory we could have the same port if we simulate several different
	 * remote hosts. But, for unit testing purposes, it is likely an unnecessary
	 * overhead/complication
	 */
	private static final int START_OF_REMOTE_EPHEMERAL_PORTS = 40000; 

	/**
	 * Set of listening ports locally opened by the SUTs.
	 * Eg, when the SUTs work as a server, we keep track
	 * of which local ports/interfaces they listen to.
	 */
	private final Set<EndPointInfo> localListeningPorts;

	/**
	 * Set of addresses/ports the SUT tried to contact.
	 */
	private final Set<EndPointInfo> remoteContactedPorts;

	private final Map<EndPointInfo, Queue<RemoteTcpServer>> remoteCurrentServers;

	/**
	 * Buffer of incoming connections.
	 * 
	 * <p>
	 * Key -> local address/port
	 * <p>
	 * Value -> queue of foreign addresses/ports waiting to connect to the given local address (key)
	 */
	private final Map<EndPointInfo,Queue<NativeTcp>> incomingConnections;

	/**
	 * Keep track of all TCP connections opened during the tests.
	 * This is for example useful to check what data the SUT sent.
	 */
	private final Set<NativeTcp> openedTcpConnections;

	/**
	 * Current remote port number that can be opened
	 */
	private final AtomicInteger remotePortIndex;

	/**
	 * Define what interfaces are available:
	 * eg, a loopback one and a wifi
	 */
	private final List<NetworkInterfaceState> networkInterfaces;


    /**
     * Key -> resolved URL (ie based on DNS) of the remote file
     * Value -> the remote file we ll allow the tests to read from
     *
     * <p>
     *    This data structure represents remote files that are on a different host, and that could be accessed
     * for example by http/s using an URL object.
     *
     * <p>
     * For simplicity, we focus on text files (eg webpages), as those are the most common example.
     *
     * <p>
     * Note: ideally we should have a full mock of remote servers. For example, accessing a http URL
     * should be equivalent to open a TCP socket and send a GET command manually. However, as we
     * do unit testing, this level of realism seems unnecessary (and anyway far too complicated to
     * implement at the moment).
     */
    private final Map<String , RemoteFile> remoteFiles;

    /**
     * Keep track of what remote URL the SUT tried to access/read from
     */
    private final Set<String> remoteAccessedFiles;

	private DNS dns;

	/**
	 * private, singleton constructor
	 */
	private VirtualNetwork(){
		localListeningPorts = new CopyOnWriteArraySet<>();
		incomingConnections = new ConcurrentHashMap<>();
		openedTcpConnections = new CopyOnWriteArraySet<>();
		remotePortIndex = new AtomicInteger(START_OF_REMOTE_EPHEMERAL_PORTS);
		remoteContactedPorts = new CopyOnWriteArraySet<>();
		remoteCurrentServers = new ConcurrentHashMap<>();
		networkInterfaces = new CopyOnWriteArrayList<>();
        remoteFiles = new ConcurrentHashMap<>();
        remoteAccessedFiles = new CopyOnWriteArraySet<>();

		dns = new DNS();
	}

	public static VirtualNetwork getInstance(){
		return instance;
	}

	//------------------------------------------

	
	public void reset(){		
		dns = new DNS();

		localListeningPorts.clear();
		incomingConnections.clear();
		remotePortIndex.set(START_OF_REMOTE_EPHEMERAL_PORTS);
		remoteCurrentServers.clear();
		networkInterfaces.clear();
        remoteFiles.clear();

		//TODO most likely it ll need different handling, as needed after the search
		openedTcpConnections.clear();
		remoteContactedPorts.clear();
        remoteAccessedFiles.clear();
	}

	public void init(){
		reset(); //just to be sure

		initNetworkInterfaces();
		MockURL.initStaticState();
	}

    /**
     * Create a new remote file that can be accessed by the given URL
     * @param url
     * @param content
     * @return {@code false} if URL is malformed, if the protocol is not a remote one (eg "file"), or
     * if the file was already created
     */
    public boolean addRemoteTextFile(String url, String content){

        URL mockURL;
        try {
            /*
                be sure to use the mocked URL, in case we have DNS resolution
             */
            mockURL = MockURL.URL(url);
        } catch (MalformedURLException e) {
            return  false;
        }
        if(mockURL.getProtocol().toLowerCase().equals("file")){
            return false; // those are handled in VFS
        }

        String key = url.toString();
        if(remoteFiles.containsKey(key)){
            return false;
        }

        RemoteFile rf = new RemoteFile(key,content);
        remoteFiles.put(key,rf);

        return true;
    }

    /**
     * If it is present on the VNET, return a remote file handler to read such file pointed by the URL.
     *
     * @param url
     * @return {@code null} if there is no such file
     */
    public RemoteFile getFile(URL url){
        String s = url.toString();
        if(!remoteAccessedFiles.contains(s)){
            remoteAccessedFiles.add(s);
        }
        return remoteFiles.get(s);
    }

	/**
	 * Create new port to open on remote host
	 * 
	 * @return a integer representing a port number on remote host
	 */
	public int getNewRemoteEphemeralPort(){
		return remotePortIndex.getAndIncrement();
	}

	/**
	 * 
	 * @param name
	 * @return {@code null} if the interface does not exist
	 */
	public NetworkInterfaceState getNetworkInterfaceState(String name){
		for(NetworkInterfaceState ni : networkInterfaces){
			if(ni.getNetworkInterface().getName().equals(name)){
				return ni;
			}
		}
		return null; 
	}

	/**
	 * Get a copy of all available interfaces
	 * @return
	 */
	public List<NetworkInterfaceState> getAllNetworkInterfaceStates(){
		return new ArrayList<NetworkInterfaceState>(networkInterfaces); 
	}

	/**
	 * Use mocked DNS to resolve host
	 * @param host
	 * @return 
	 */
	public String dnsResolve(String host){
		return dns.resolve(host);
	}

	/**
	 * Simulate an incoming connection. The connection is put on a buffer till
	 * the SUT open a listening port
	 * 
	 * @param originAddr
	 * @param originPort
	 * @param destAddr
	 * @param destPort
	 */
	public synchronized NativeTcp registerIncomingTcpConnection(
			String originAddr, int originPort,
			String destAddr, int destPort){

		EndPointInfo origin = new EndPointInfo(originAddr,originPort,ConnectionType.TCP);
		EndPointInfo dest = new EndPointInfo(destAddr,destPort,ConnectionType.TCP);

		Queue<NativeTcp> queue = incomingConnections.get(dest);
		if(queue == null){
			queue = new ConcurrentLinkedQueue<>();
			incomingConnections.put(dest, queue);
		}

		NativeTcp connection = new NativeTcp(dest,origin);		
		queue.add(connection);
		return connection;
	}

	/**
	 * Return a TCP connection for the given local address if there is any inbound remote connection to it
	 * 
	 * @param localAddress
	 * @param localPort
	 * @return  {@code null} if the test case has not set up it an incoming TCP connection
	 */
	public synchronized NativeTcp pullTcpConnection(String localAddress, int localPort){
		EndPointInfo local = new EndPointInfo(localAddress,localPort,ConnectionType.TCP);
		Queue<NativeTcp> queue = incomingConnections.get(local);
		if(queue == null || queue.isEmpty()){
			return null;
		}

		NativeTcp connection = queue.poll();
		openedTcpConnections.add(connection);

		return connection; 
	}


	/**
	 * 
	 * @param addr
	 * @return {@code false} if it was not possible to open the listening port
	 */
	public synchronized boolean  openTcpServer(String addr, int port){
		EndPointInfo info = new EndPointInfo(addr,port,ConnectionType.TCP);

		if(localListeningPorts.contains(info)){
			//there is already an existing opened port
			return false;
		}

		if(! isValidLocalServer(info)){
			return false;
		}

		localListeningPorts.add(info);

		return true;
	}

    public Set<String> getViewOfRemoteAccessedFiles(){
        return Collections.unmodifiableSet(remoteAccessedFiles);
    }

	public Set<NativeTcp> getViewOfOpenedTcpConnections(){
		return  Collections.unmodifiableSet(openedTcpConnections);
	}

	/**
	 *  Register a remote server that can reply to SUT's connection requests
	 */
	public synchronized void addRemoteTcpServer(RemoteTcpServer server){

		Queue<RemoteTcpServer> queue = remoteCurrentServers.get(server.getAddress());
		if(queue==null){
			queue = new ConcurrentLinkedQueue<>();
			remoteCurrentServers.put(server.getAddress(), queue);
		}

		queue.add(server);
	}



	public synchronized NativeTcp connectToRemoteAddress(EndPointInfo localOrigin, EndPointInfo remoteTarget)
			throws IllegalArgumentException, IOException{

		if(localOrigin==null || remoteTarget==null){
			throw new IllegalArgumentException("Null input");
		}

		if(!isValidLocalServer(localOrigin)){
			throw new IllegalArgumentException("Invalid local address: "+localOrigin);
		}

		remoteContactedPorts.add(remoteTarget);

		Queue<RemoteTcpServer> queue = remoteCurrentServers.get(remoteTarget);
		if(queue==null || queue.isEmpty()){
			throw new IOException("Remote address/port is not opened: "+remoteTarget);
		}

		RemoteTcpServer server = queue.poll();
		NativeTcp connection = server.connect(localOrigin);
		return connection;
	}

	//------------------------------------------


	private boolean isValidLocalServer(EndPointInfo info){
		return true; //TODO
	}

	private void initNetworkInterfaces() {

		try{
			NetworkInterfaceState loopback = new NetworkInterfaceState(
					"Evo_lo0", 1, null, 16384, true, MockInetAddress.getByName("127.0.0.1"));
			networkInterfaces.add(loopback);

			NetworkInterfaceState wifi = new NetworkInterfaceState(
					"Evo_en0", 5, new byte[]{0, 42, 0, 42, 0, 42}, 
					1500, false, MockInetAddress.getByName("142.42.42.42"));
			networkInterfaces.add(wifi);
		} catch(Exception e){
			//this should never happen
			throw new RuntimeException("EvoSuite error: "+e.getMessage());
		}
	}

	//------------------------------------------
}
