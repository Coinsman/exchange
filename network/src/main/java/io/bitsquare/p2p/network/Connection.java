package io.bitsquare.p2p.network;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.Uninterruptibles;
import io.bitsquare.app.Log;
import io.bitsquare.common.ByteArrayUtils;
import io.bitsquare.common.UserThread;
import io.bitsquare.p2p.Address;
import io.bitsquare.p2p.Message;
import io.bitsquare.p2p.Utils;
import io.bitsquare.p2p.network.messages.CloseConnectionMessage;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Connection is created by the server thread or by sendMessage from NetworkNode.
 * All handlers are called on User thread.
 * Shared data between InputHandler thread and that
 */
public class Connection implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(Connection.class);
    private static final int MAX_MSG_SIZE = 5 * 1024 * 1024;         // 5 MB of compressed data
    private static final int MAX_ILLEGAL_REQUESTS = 5;
    private static final int SEND_MESSAGE_TIMEOUT = 10 * 1000;        // 10 sec.
    private static final int SOCKET_TIMEOUT = 30 * 60 * 1000;        // 30 min.
    private InputHandler inputHandler;
    private volatile boolean isAuthenticated;
    private String connectionId;

    public static int getMaxMsgSize() {
        return MAX_MSG_SIZE;
    }

    private final Socket socket;
    private final MessageListener messageListener;
    private final ConnectionListener connectionListener;

    private final String portInfo;
    private final String uid;
    private final ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
    public final String objectId = super.toString().split("@")[1];

    // set in init
    private ObjectOutputStream objectOutputStream;
    // holder of state shared between InputHandler and Connection
    private SharedSpace sharedSpace;

    // mutable data, set from other threads but not changed internally.
    @Nullable
    private Address peerAddress;

    private volatile boolean stopped;

    //TODO got java.util.zip.DataFormatException: invalid distance too far back
    // java.util.zip.DataFormatException: invalid literal/lengths set
    // use GZIPInputStream but problems with blocking
    private final boolean useCompression = false;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Connection(Socket socket, MessageListener messageListener, ConnectionListener connectionListener) {
        this.socket = socket;
        this.messageListener = messageListener;
        this.connectionListener = connectionListener;

        Log.traceCall();
        uid = UUID.randomUUID().toString();
        if (socket.getLocalPort() == 0)
            portInfo = "port=" + socket.getPort();
        else
            portInfo = "localPort=" + socket.getLocalPort() + "/port=" + socket.getPort();

        init();
    }

    private void init() {
        Log.traceCall();
        sharedSpace = new SharedSpace(this, socket);
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT);
            // Need to access first the ObjectOutputStream otherwise the ObjectInputStream would block
            // See: https://stackoverflow.com/questions/5658089/java-creating-a-new-objectinputstream-blocks/5658109#5658109
            // When you construct an ObjectInputStream, in the constructor the class attempts to read a header that 
            // the associated ObjectOutputStream on the other end of the connection has written.
            // It will not return until that header has been read. 
            objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream objectInputStream = new ObjectInputStream(socket.getInputStream());


            // We create a thread for handling inputStream data
            inputHandler = new InputHandler(sharedSpace, objectInputStream, portInfo, this, useCompression);
            singleThreadExecutor.submit(inputHandler);
        } catch (IOException e) {
            sharedSpace.handleConnectionException(e);
        }

        sharedSpace.updateLastActivityDate();

        log.trace("\nNew connection created " + this.toString());
        UserThread.execute(() -> connectionListener.onConnection(this));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Called form UserThread
    public void setAuthenticated(Address peerAddress, Connection connection) {
        Log.traceCall();
        this.peerAddress = peerAddress;
        isAuthenticated = true;
        if (!stopped)
            connectionListener.onPeerAddressAuthenticated(peerAddress, connection);
    }

    // Called form various threads
    public void sendMessage(Message message) {
        Log.traceCall();
        if (!stopped) {
            try {
                log.info("writeObject " + message + " on connection with port " + portInfo);
                Object objectToWrite;
                if (useCompression) {
                    byte[] messageAsBytes = ByteArrayUtils.objectToByteArray(message);
                    // log.trace("Write object uncompressed data size: " + messageAsBytes.length);
                    byte[] compressed = Utils.compress(message);
                    //log.trace("Write object compressed data size: " + compressed.length);
                    objectToWrite = compressed;
                } else {
                    // log.trace("Write object data size: " + ByteArrayUtils.objectToByteArray(message).length);
                    objectToWrite = message;
                }
                if (!stopped) {
                    synchronized (objectOutputStream) {
                        objectOutputStream.writeObject(objectToWrite);
                        objectOutputStream.flush();
                    }
                    sharedSpace.updateLastActivityDate();
                }
            } catch (IOException e) {
                // an exception lead to a shutdown
                sharedSpace.handleConnectionException(e);
            }
        } else {
            log.debug("called sendMessage but was already stopped");
        }
    }

    public void reportIllegalRequest(IllegalRequest illegalRequest) {
        Log.traceCall();
        sharedSpace.reportIllegalRequest(illegalRequest);
    }

    public synchronized void setPeerAddress(@Nullable Address peerAddress) {
        Log.traceCall();
        this.peerAddress = peerAddress;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Only get non - CloseConnectionMessage messages
    @Override
    public void onMessage(Message message, Connection connection) {
        // connection is null as we get called from InputHandler, which does not hold a reference to Connection
        UserThread.execute(() -> messageListener.onMessage(message, this));
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Nullable
    public synchronized Address getPeerAddress() {
        //Log.traceCall();
        return peerAddress;
    }

    public Date getLastActivityDate() {
        //Log.traceCall();
        return sharedSpace.getLastActivityDate();
    }

    public boolean isAuthenticated() {
        //Log.traceCall();
        return isAuthenticated;
    }

    public String getUid() {
        Log.traceCall();
        return uid;
    }

    public boolean isStopped() {
        Log.traceCall();
        return stopped;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // ShutDown
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void shutDown(Runnable completeHandler) {
        // Log.traceCall();
        shutDown(true, completeHandler);
    }

    public void shutDown() {
        //Log.traceCall();
        shutDown(true, null);
    }

    private void shutDown(boolean sendCloseConnectionMessage) {
        //Log.traceCall();
        shutDown(sendCloseConnectionMessage, null);
    }

    private void shutDown(boolean sendCloseConnectionMessage, @Nullable Runnable shutDownCompleteHandler) {
        Log.traceCall(this.toString());
        if (!stopped) {
            log.info("\n\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n" +
                    "ShutDown connection:"
                    + "\npeerAddress=" + peerAddress
                    + "\nlocalPort/port=" + sharedSpace.getSocket().getLocalPort()
                    + "/" + sharedSpace.getSocket().getPort()
                    + "\nobjectId=" + objectId + " / uid=" + uid
                    + "\nisAuthenticated=" + isAuthenticated
                    + "\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n");

            log.trace("ShutDown connection requested. Connection=" + this.toString());

            if (sendCloseConnectionMessage) {
                new Thread(() -> {
                    Thread.currentThread().setName("Connection:SendCloseConnectionMessage-" + this.objectId);
                    Log.traceCall("sendCloseConnectionMessage");
                    try {
                        sendMessage(new CloseConnectionMessage());
                        // TODO increase delay
                        Uninterruptibles.sleepUninterruptibly(200, TimeUnit.MILLISECONDS);
                    } catch (Throwable t) {
                        log.error(t.getMessage());
                        t.printStackTrace();
                    } finally {
                        UserThread.execute(() -> continueShutDown(shutDownCompleteHandler));
                    }
                }).start();
            } else {
                continueShutDown(shutDownCompleteHandler);
            }
        }
    }

    private void continueShutDown(@Nullable Runnable shutDownCompleteHandler) {
        Log.traceCall();

        stopped = true;
        sharedSpace.stop();
        if (inputHandler != null)
            inputHandler.stop();
        ConnectionListener.Reason shutDownReason = sharedSpace.getShutDownReason();
        if (shutDownReason == null)
            shutDownReason = ConnectionListener.Reason.SHUT_DOWN;
        final ConnectionListener.Reason finalShutDownReason = shutDownReason;
        // keep UserThread.execute as its not clear if that is called from a non-UserThread
        UserThread.execute(() -> connectionListener.onDisconnect(finalShutDownReason, this));

        try {
            sharedSpace.getSocket().close();
        } catch (SocketException e) {
            log.trace("SocketException at shutdown might be expected " + e.getMessage());
        } catch (IOException e) {
            log.error("Exception at shutdown. " + e.getMessage());
            e.printStackTrace();
        } finally {
            MoreExecutors.shutdownAndAwaitTermination(singleThreadExecutor, 500, TimeUnit.MILLISECONDS);

            log.debug("Connection shutdown complete " + this.toString());
            // keep UserThread.execute as its not clear if that is called from a non-UserThread

            if (shutDownCompleteHandler != null)
                UserThread.execute(shutDownCompleteHandler);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Connection)) return false;

        Connection that = (Connection) o;

        if (portInfo != null ? !portInfo.equals(that.portInfo) : that.portInfo != null) return false;
        if (uid != null ? !uid.equals(that.uid) : that.uid != null) return false;
        return !(peerAddress != null ? !peerAddress.equals(that.peerAddress) : that.peerAddress != null);

    }

    @Override
    public int hashCode() {
        int result = portInfo != null ? portInfo.hashCode() : 0;
        result = 31 * result + (uid != null ? uid.hashCode() : 0);
        result = 31 * result + (peerAddress != null ? peerAddress.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Connection{" +
                "portInfo=" + portInfo +
                ", uid='" + uid + '\'' +
                ", objectId='" + objectId + '\'' +
                ", sharedSpace=" + sharedSpace.toString() +
                ", peerAddress=" + peerAddress +
                ", isAuthenticated=" + isAuthenticated +
                ", stopped=" + stopped +
                ", stopped=" + stopped +
                ", useCompression=" + useCompression +
                '}';
    }

    public String getConnectionId() {
        return connectionId;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // SharedSpace
    ///////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Holds all shared data between Connection and InputHandler
     * Runs in same thread as Connection
     */
    private static class SharedSpace {
        private static final Logger log = LoggerFactory.getLogger(SharedSpace.class);

        private final Connection connection;
        private final Socket socket;
        private final ConcurrentHashMap<IllegalRequest, Integer> illegalRequests = new ConcurrentHashMap<>();

        // mutable
        private Date lastActivityDate;
        private volatile boolean stopped;
        private ConnectionListener.Reason shutDownReason;

        public SharedSpace(Connection connection, Socket socket) {
            Log.traceCall();
            this.connection = connection;
            this.socket = socket;
        }

        public synchronized void updateLastActivityDate() {
            Log.traceCall();
            lastActivityDate = new Date();
        }

        public synchronized Date getLastActivityDate() {
            // Log.traceCall();
            return lastActivityDate;
        }

        public void reportIllegalRequest(IllegalRequest illegalRequest) {
            Log.traceCall();
            log.warn("We got reported an illegal request " + illegalRequest);
            int prevCounter = illegalRequests.get(illegalRequest);
            if (prevCounter > illegalRequest.maxTolerance) {
                log.warn("We close connection as we received too many illegal requests.\n" + illegalRequests.toString());
                connection.shutDown(false);
            } else {
                illegalRequests.put(illegalRequest, ++prevCounter);
            }
        }

        public void handleConnectionException(Exception e) {
            Log.traceCall(e.toString());
            log.debug("Exception might be expected: " + e.toString());
            if (e instanceof SocketException) {
                if (socket.isClosed())
                    shutDownReason = ConnectionListener.Reason.SOCKET_CLOSED;
                else
                    shutDownReason = ConnectionListener.Reason.RESET;
            } else if (e instanceof SocketTimeoutException || e instanceof TimeoutException) {
                shutDownReason = ConnectionListener.Reason.TIMEOUT;
            } else if (e instanceof EOFException) {
                shutDownReason = ConnectionListener.Reason.PEER_DISCONNECTED;
            } else {
                shutDownReason = ConnectionListener.Reason.UNKNOWN;
                log.info("Exception at connection with port " + socket.getLocalPort());
                e.printStackTrace();
            }

            if (!stopped) {
                stopped = true;
                connection.shutDown(false);
            }
        }

        public void shutDown(boolean sendCloseConnectionMessage) {
            Log.traceCall();
            connection.shutDown(sendCloseConnectionMessage);
        }


        public synchronized Socket getSocket() {
            return socket;
        }

        public String getConnectionId() {
            return connection.getConnectionId();
        }

        public void stop() {
            Log.traceCall();
            this.stopped = true;
        }

        public synchronized ConnectionListener.Reason getShutDownReason() {
            return shutDownReason;
        }

        @Override
        public String toString() {
            return "SharedSpace{" +
                    ", socket=" + socket +
                    ", illegalRequests=" + illegalRequests +
                    ", lastActivityDate=" + lastActivityDate +
                    '}';
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // InputHandler
    ///////////////////////////////////////////////////////////////////////////////////////////

    // Runs in same thread as Connection
    private static class InputHandler implements Runnable {
        private static final Logger log = LoggerFactory.getLogger(InputHandler.class);

        private final SharedSpace sharedSpace;
        private final ObjectInputStream objectInputStream;
        private final String portInfo;
        private final MessageListener messageListener;
        private final boolean useCompression;

        private volatile boolean stopped;

        public InputHandler(SharedSpace sharedSpace, ObjectInputStream objectInputStream, String portInfo, MessageListener messageListener, boolean useCompression) {
            this.useCompression = useCompression;
            Log.traceCall();
            this.sharedSpace = sharedSpace;
            this.objectInputStream = objectInputStream;
            this.portInfo = portInfo;
            this.messageListener = messageListener;
        }

        public void stop() {
            Log.traceCall();
            stopped = true;
        }

        @Override
        public void run() {
            Log.traceCall();
            try {
                Thread.currentThread().setName("InputHandler-" + portInfo);
                while (!stopped && !Thread.currentThread().isInterrupted()) {
                    try {
                        log.trace("InputHandler waiting for incoming messages connection=" + sharedSpace.getConnectionId());
                        Object rawInputObject = objectInputStream.readObject();
                        log.info("New data arrived at inputHandler of connection=" + sharedSpace.getConnectionId()
                                + " rawInputObject " + rawInputObject);

                        int size = ByteArrayUtils.objectToByteArray(rawInputObject).length;
                        if (size <= getMaxMsgSize()) {
                            Serializable serializable = null;
                            if (useCompression) {
                                if (rawInputObject instanceof byte[]) {
                                    byte[] compressedObjectAsBytes = (byte[]) rawInputObject;
                                    size = compressedObjectAsBytes.length;
                                    //log.trace("Read object compressed data size: " + size);
                                    serializable = Utils.decompress(compressedObjectAsBytes);
                                } else {
                                    sharedSpace.reportIllegalRequest(IllegalRequest.InvalidDataType);
                                }
                            } else {
                                if (rawInputObject instanceof Serializable) {
                                    serializable = (Serializable) rawInputObject;
                                } else {
                                    sharedSpace.reportIllegalRequest(IllegalRequest.InvalidDataType);
                                }
                            }
                            //log.trace("Read object decompressed data size: " + ByteArrayUtils.objectToByteArray(serializable).length);

                            // compressed size might be bigger theoretically so we check again after decompression
                            if (size <= getMaxMsgSize()) {
                                if (serializable instanceof Message) {
                                    sharedSpace.updateLastActivityDate();
                                    Message message = (Message) serializable;
                                    if (message instanceof CloseConnectionMessage) {
                                        stopped = true;
                                        sharedSpace.shutDown(false);
                                    } else if (!stopped) {
                                        messageListener.onMessage(message, null);
                                    }
                                } else {
                                    sharedSpace.reportIllegalRequest(IllegalRequest.InvalidDataType);
                                }
                            } else {
                                sharedSpace.reportIllegalRequest(IllegalRequest.MaxSizeExceeded);
                            }
                        } else {
                            sharedSpace.reportIllegalRequest(IllegalRequest.MaxSizeExceeded);
                        }
                    } catch (IOException | ClassNotFoundException e) {
                        stopped = true;
                        sharedSpace.handleConnectionException(e);
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
                stopped = true;
                sharedSpace.handleConnectionException(new Exception(t));
            }
        }

        @Override
        public String toString() {
            return "InputHandler{" +
                    "sharedSpace=" + sharedSpace +
                    ", port=" + portInfo +
                    ", stopped=" + stopped +
                    '}';
        }
    }
}