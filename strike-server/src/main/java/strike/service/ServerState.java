package strike.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import strike.model.*;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServerState {
    private static final Logger logger = LogManager.getLogger(ServerState.class);

    private static ServerState instance;

    private ConcurrentMap<String, Integer> aliveMap;
    private ConcurrentMap<String, UserSession> localUserSessions;
    private ConcurrentMap<String, RemoteUserSession> remoteUserSessions;
    private ConcurrentMap<String, LocalChatRoomInfo> localChatRooms;
    private ConcurrentMap<String, RemoteChatRoomInfo> remoteChatRooms;
    private ConcurrentMap<String, UserInfo> connectedClients;

    private Set<String> lockedIdentities;
    private Set<String> lockedRoomIdentities;

    private ConcurrentMap<String, ServerInfo> serverInfoMap;
    private ConcurrentNavigableMap<String, ServerInfo> candidateServerInfoMap;
    private Map<String, ServerInfo> subordinateServerInfoMap;
    private List<ServerInfo> serverInfoList;
    private ServerInfo serverInfo;
    // elected coordinator for the server cluster
    private ServerInfo coordinator;

    private AtomicBoolean stopRunning = new AtomicBoolean(false);

    private Long electionAnswerTimeout;
    private Long electionCoordinatorTimeout;

//    private Scheduler simpleScheduler;

    private ServerState() {
        aliveMap = new ConcurrentHashMap<>();
        localUserSessions = new ConcurrentHashMap<>();
        remoteUserSessions = new ConcurrentHashMap<>();
        connectedClients = new ConcurrentHashMap<>();
        localChatRooms = new ConcurrentHashMap<>();
        remoteChatRooms = new ConcurrentHashMap<>();
        lockedIdentities = new HashSet<>();
        lockedRoomIdentities = new HashSet<>();
        serverInfoMap = new ConcurrentHashMap<>();
        // ConcurrentSkipListMap is thread safe and ordered by key
        candidateServerInfoMap = new ConcurrentSkipListMap<>(new ServerPriorityComparator());
        subordinateServerInfoMap = new ConcurrentHashMap<>();
//        synchronized (ServerState.class){
//            SchedulerFactory schedulerFactory = new StdSchedulerFactory();
//            try {
//                simpleScheduler = schedulerFactory.getScheduler();
//            } catch (SchedulerException e) {
//                logger.error("Error while creating scheduler : " + e.getLocalizedMessage());
//            }
//        }
    }

    public static synchronized ServerState getInstance() {
        if (instance == null) {
            instance = new ServerState();
        }
        return instance;
    }

    public synchronized void initServerState(String serverId) {
        serverInfo = serverInfoMap.get(serverId);
/*
        serverInfo = serverInfoList.stream()
                .filter(e -> e.getServerId().equalsIgnoreCase(serverId))
                .findFirst()
                .get();
*/
    }

    public synchronized ServerInfo getServerInfoById(String serverId) {
        return serverInfoMap.get(serverId);
/*
        return serverInfoList.stream()
                .filter(e -> e.getServerId().equalsIgnoreCase(serverId))
                .findFirst()
                .get();
*/
    }

    public synchronized ServerInfo getServerInfo() {
        return serverInfo;
    }

    public synchronized List<ServerInfo> getServerInfoList() {
        //return serverInfoList;
        return new ArrayList<>(serverInfoMap.values());
    }

    public synchronized List<ServerInfo> getCandidateServerInfoList(){
        return new ArrayList<>(candidateServerInfoMap.values());
    }

    public synchronized List<ServerInfo> getSubordinateServerInfoList() {
        return new ArrayList<>(subordinateServerInfoMap.values());
    }

    public synchronized void setServerInfoList(List<ServerInfo> serverInfoList) {
        //this.serverInfoList = serverInfoList;
        for (ServerInfo serverInfo : serverInfoList) {
            addServer(serverInfo);
        }
    }

    public synchronized void addServer(ServerInfo serverInfo) {
        ServerInfo me = getServerInfo();
        if (null != me && null != serverInfo) {
            if (new ServerPriorityComparator().compare(me.getServerId(), serverInfo.getServerId()) > 0) {
                candidateServerInfoMap.put(serverInfo.getServerId(), serverInfo);
            } else if (new ServerPriorityComparator().compare(me.getServerId(), serverInfo.getServerId()) < 0) {
                subordinateServerInfoMap.put(serverInfo.getServerId(), serverInfo);
            }
        }
        serverInfoMap.put(serverInfo.getServerId(), serverInfo);


/*
        for (int i = 0; i < serverInfoList.size(); i++) {
            ServerInfo s = serverInfoList.get(i);
            if (s.getServerId().equalsIgnoreCase(serverInfo.getServerId())) {
                logger.info("Server " + serverInfo.getServerId() + " already exist.");
            } else {
                if (!Objects.equals(s.getPort(), serverInfo.getPort())) {
                    logger.info("Adding server " + serverInfo.getServerId() + " to server list.");
                    serverInfoList.add(serverInfo);
                }
            }
        }
*/
    }

    public synchronized void setupConnectedServers(){
        for (ServerInfo server : getServerInfoList()){
            addServer(server);
        }
    }

    public synchronized ServerInfo getNextPotentialCandidate(String serverId){
        return candidateServerInfoMap.ceilingEntry(serverId).getValue();
    }

    public synchronized void removeServer(String serverId) {
        serverInfoMap.remove(serverId);
    }

    // thread safe

    public ConcurrentMap<String, Integer> getAliveMap() {
        return aliveMap;
    }

    public ConcurrentMap<String, UserSession> getLocalUserSessions() {
        return localUserSessions;
    }

    public ConcurrentMap<String, RemoteUserSession> getRemoteUserSessions() {
        return remoteUserSessions;
    }

    public ConcurrentMap<String, UserInfo> getConnectedClients() {
        return connectedClients;
    }

    public ConcurrentMap<String, LocalChatRoomInfo> getLocalChatRooms() {
        return localChatRooms;
    }

    public ConcurrentMap<String, RemoteChatRoomInfo> getRemoteChatRooms() {
        return remoteChatRooms;
    }

    public boolean isUserExisted(String userId) {
        return connectedClients.containsKey(userId);
    }

    public boolean isRoomExistedGlobally(String roomId) {
        return localChatRooms.containsKey(roomId) || remoteChatRooms.containsKey(roomId);
    }

    public boolean isRoomExistedLocally(String roomId) {
        return localChatRooms.containsKey(roomId);
    }

    public boolean isRoomExistedRemotely(String roomId) {
        return remoteChatRooms.containsKey(roomId);
    }

    public void stopRunning(boolean state) {
        stopRunning.set(state);
    }

    public boolean isStopRunning() {
        return stopRunning.get();
    }

    public void removeRemoteChatRoomsByServerId(String serverId) {
        for (String entry : remoteChatRooms.keySet()) {
            RemoteChatRoomInfo remoteChatRoomInfo = remoteChatRooms.get(entry);
            if (remoteChatRoomInfo.getManagingServer().equalsIgnoreCase(serverId)) {
                remoteChatRooms.remove(entry);
            }
        }
    }

    public void removeRemoteUserSessionsByServerId(String serverId) {
        for (String entry : remoteUserSessions.keySet()) {
            RemoteUserSession remoteUserSession = remoteUserSessions.get(entry);
            if (remoteUserSession.getManagingServerId().equalsIgnoreCase(serverId)) {
                remoteUserSessions.remove(entry);
            }
        }
    }

    // synchronized

    public synchronized void lockIdentity(String identity) {
        lockedIdentities.add(identity);
    }

    public synchronized void unlockIdentity(String identity) {
        lockedIdentities.remove(identity);
    }

    public synchronized boolean isIdentityLocked(String identity) {
        return lockedIdentities.contains(identity);
    }

    public synchronized void lockRoomIdentity(String roomId) {
        lockedRoomIdentities.add(roomId);
    }

    public synchronized void unlockRoomIdentity(String roomId) {
        lockedRoomIdentities.remove(roomId);
    }

    public synchronized boolean isRoomIdLocked(String roomId) {
        return lockedRoomIdentities.contains(roomId);
    }

    // Utilities

    public static final int MIN_CHAR = 2;
    public static final int MAX_CHAR = 17;

    public boolean isIdValid(String id) {
        // The identity must be
        // an alphanumeric string starting with an upper or lower case character.
        // It must be at least 3 characters and no more than 16 characters long
        int length = id.length();
        return (StringUtils.isAlphanumeric(id) && length > MIN_CHAR && length < MAX_CHAR);
    }

    public boolean isOnline(ServerInfo serverInfo) {
        boolean online = true;
        try {
            InetSocketAddress address = new InetSocketAddress(serverInfo.getAddress(), serverInfo.getManagementPort());
            final int timeOut = (int) TimeUnit.SECONDS.toMillis(5);
            SSLSocketFactory sslsocketfactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            final SSLSocket shortKet = (SSLSocket) sslsocketfactory.createSocket();
            shortKet.connect(address, timeOut);
            shortKet.startHandshake();
            shortKet.close();
        } catch (IOException e) {
            //e.printStackTrace();
            online = false;
        }
        return online;
    }


    public synchronized ServerInfo getCoordinator() {
        return coordinator;
    }

    public synchronized void setCoordinator(ServerInfo coordinator) {
        this.coordinator = coordinator;
    }

    public Long getElectionAnswerTimeout() {
        return electionAnswerTimeout;
    }

    public void setElectionAnswerTimeout(Long electionAnswerTimeout) {
        this.electionAnswerTimeout = electionAnswerTimeout;
    }

    public Long getElectionCoordinatorTimeout() {
        return electionCoordinatorTimeout;
    }

    public void setElectionCoordinatorTimeout(Long electionCoordinatorTimeout) {
        this.electionCoordinatorTimeout = electionCoordinatorTimeout;
    }
}
