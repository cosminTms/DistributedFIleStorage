import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class FileIndex {
    //private Controller controller;
    private int timeout;
    private int rebalancePeriod;
    //private long lastRebalance;
    //private long rebalanceTimer;
    //private RebalancePhase rebalancePhase = RebalancePhase.AVAILABLE;
    private AtomicBoolean dataStoreJoined = new AtomicBoolean(false);
    //private ReentrantReadWriteLock rebalanceLock = new ReentrantReadWriteLock(true);
    private ReentrantReadWriteLock updateLock = new ReentrantReadWriteLock(true);
    // map filename to their state in the index (eg. STORE_IN_PROGRESS, STORE_COMPLETE, REMOVE_IN_PROGRESS)
    private ConcurrentHashMap<String,IndexState> fileToState = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String,Long> fileToStateTimer = new ConcurrentHashMap<>();
    // map filename to their file size
    private ConcurrentHashMap<String,Integer> fileToSize = new ConcurrentHashMap<>();
    // map filename to the list of dstores storing them
    private ConcurrentHashMap<String, List<Socket>> filesToDstores = new ConcurrentHashMap<>();
    // map dstore to the list of the files they store
    private ConcurrentHashMap<Socket, List<String>> dstoresToFiles = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Socket, Integer> dstoresToNoOfFiles = new ConcurrentHashMap<>();
    // map files to list of clients
    private ConcurrentHashMap<String, Socket> filesToStateClients = new ConcurrentHashMap<>();
    //map client to the list of dstores they tried to load each file from
    //private ConcurrentHashMap<TCPSender, ConcurrentHashMap<String, List<TCPSender>>> clientToDstoresLoadingFrom = new ConcurrentHashMap<>();
    // map filename to the number of acks needed to store them
    private ConcurrentHashMap<String, AtomicInteger> filesToAcks = new ConcurrentHashMap<>();
    // client Socket to a HashMap of files and dstoreSockets used to load from
    private ConcurrentHashMap<Socket, ConcurrentHashMap<String, List<Socket>>> loadAttempts = new ConcurrentHashMap<>();
    // list of files each dstore has sent during a rebalance operation
    //private ConcurrentHashMap<TCPSender, List<String>> rebalancingDstoresToFiles = new ConcurrentHashMap<>();
    //private ConcurrentHashMap<TCPSender,ConcurrentHashMap<String, List<TCPSender>>> rebalancingDstores = new ConcurrentHashMap<>();
    //private ConcurrentHashMap<TCPSender, List<String>> removeFilesFromDstore = new ConcurrentHashMap<>();
    //private Vector<TCPSender> remainigDstoresToSendRebalanceAck = new Vector<>();

    public enum IndexState{
        STORE_IN_PROGRESS,
        STORE_COMPLETE,
        REMOVE_IN_PROGRESS;
    }

    public FileIndex(int timeout, int rebalancePeriod){
        this.timeout = timeout;
        this.rebalancePeriod = rebalancePeriod;
        //this.lastRebalance = System.currentTimeMillis();
    }

    public void dstoreJoin(Socket dstore){
        //dataStoreJoined.set(true);
        dstoresToFiles.put(dstore, new Vector<>());
        dstoresToNoOfFiles.put(dstore,0);
    }

    public void storeFile(String filename, int filesize, Socket clientSocket, List<Socket> storingDataStores){
        fileToState.put(filename, IndexState.STORE_IN_PROGRESS);
        fileToStateTimer.put(filename, System.currentTimeMillis());
        fileToSize.put(filename,filesize);
        filesToDstores.put(filename, new Vector<>());
        filesToStateClients.put(filename, clientSocket);
        filesToAcks.put(filename,new AtomicInteger(storingDataStores.size()));
        incrementDstores(storingDataStores);
    }

    public void loadFile(String fileName, Socket clientSocket, Socket dstoreSocket) {
        /**Updates the load attempts for the file.*/
        if (loadAttempts.containsKey(clientSocket)){
            if (loadAttempts.get(clientSocket).get(fileName) != null) {
                loadAttempts.get(clientSocket).get(fileName).add(dstoreSocket);
            } else{
                loadAttempts.get(clientSocket).put(fileName, new Vector<>(Arrays.asList(dstoreSocket)));
            }
        } else{
            ConcurrentHashMap<String, List<Socket>> clientLoadMap = new ConcurrentHashMap<>();
            clientLoadMap.put(fileName, new Vector<>(Arrays.asList(dstoreSocket)));
            loadAttempts.put(clientSocket, clientLoadMap);
        }
    }

    public void removeFile(String fileName, Socket clientSocket, List<Socket> storingDstores){
        fileToState.put(fileName, IndexState.REMOVE_IN_PROGRESS);
        fileToStateTimer.put(fileName, System.currentTimeMillis());
        fileToSize.remove(fileName);
        dstoresToFiles.values().forEach(fileList -> fileList.remove(fileName));
        //filesToStateClients.put(fileName, new Vector<>(Arrays.asList(clientSocket)));
        filesToStateClients.put(fileName, clientSocket);
        filesToDstores.remove(fileName);
        filesToAcks.put(fileName, new AtomicInteger(storingDstores.size()));
        decrementDstores(storingDstores);
    }

    public boolean containsFile(String filename){
        if (fileToState.get(filename) == IndexState.STORE_IN_PROGRESS || fileToState.get(filename) == IndexState.STORE_COMPLETE || fileToState.get(filename) == IndexState.REMOVE_IN_PROGRESS) {
            return true;
        }
        return false;
    }

    public void updateDstores(Socket dstore){
        for (Socket ds : dstoresToFiles.keySet()){
            if (ds == dstore){
                dstoresToFiles.remove(ds);
            }
        }

        for (String file : filesToDstores.keySet()){
            for (Socket ds : filesToDstores.get(file)){
                if (ds == dstore){
                    filesToDstores.get(file).remove(ds);
                }
            }
        }

        for (Socket ds : dstoresToNoOfFiles.keySet()){
            if (ds == dstore){
                dstoresToNoOfFiles.remove(ds);
            }
        }
    }

    public void incrementDstores(List<Socket> dstores){
        for (Socket ds : dstores){
            dstoresToNoOfFiles.put(ds,dstoresToNoOfFiles.get(ds) + 1);
        }
    }

    public void decrementDstores(List<Socket> dstores){
        for (Socket ds : dstores){
            dstoresToNoOfFiles.put(ds,dstoresToNoOfFiles.get(ds) - 1);
        }
    }

    public void update(){
        //updateLock.writeLock().lock();
            long currentTime = System.currentTimeMillis();
            List<String> expiredFiles = fileToStateTimer.entrySet().stream()
                    .filter(entry -> currentTime - entry.getValue() >= timeout)
                    .collect(Collectors.mapping(Map.Entry::getKey, Collectors.toList()));

            /**Sets expired states back to the default state.*/
            expiredFiles.forEach(file -> expireState(file, fileToState.get(file)));
    }
            //updateLock.writeLock().unlock();

    public void expireState(String filename, IndexState state){
        if (state != null) {
            switch (state) {
                case STORE_IN_PROGRESS:
                    fileToState.remove(filename);
                    fileToSize.remove(filename);
                    filesToDstores.remove(filename);
                    updateDstoresNoOfFiles(filename);
                    dstoresToFiles.values().forEach(fileList -> fileList.remove(filename));
                    filesToAcks.remove(filename);
                    System.out.println("STORE OP HAS EXPIRED");
                    break;
                case REMOVE_IN_PROGRESS:
                    fileToState.remove(filename);
                    filesToAcks.remove(filename);
                    System.out.println("REMOVE OP HAS EXPIRED");
                    break;
                default:
                    throw new IllegalArgumentException("Cannot expire a file in the STORE_COMPLETE state!");
            }
        }

        filesToStateClients.remove(filename);
        fileToStateTimer.remove(filename);
    }

    public void updateDstoresNoOfFiles(String filename){
        for (Socket ds : dstoresToFiles.keySet()){
            if (dstoresToFiles.get(ds).contains(filename)){
                dstoresToNoOfFiles.put(ds, dstoresToNoOfFiles.get(ds) - 1);
            }
        }
    }

    public ReentrantReadWriteLock getUpdateLock(){
        return updateLock;
    }

    public ConcurrentHashMap<String, IndexState> getFileToState(){
        return fileToState;
    }

    public ConcurrentHashMap<String, AtomicInteger> getFilesToAcks(){
        return filesToAcks;
    }

    public ConcurrentHashMap<String, List<Socket>> getFilesToDstores(){
        return filesToDstores;
    }

    public ConcurrentHashMap<Socket, List<String>> getDstoresToFiles(){
        return dstoresToFiles;
    }

    public ConcurrentHashMap<Socket, Integer> getDstoresToNoOfFiles() {
        return dstoresToNoOfFiles;
    }

    public ConcurrentHashMap<String, Socket> getFilesToStateClients(){
        return filesToStateClients;
    }

    public ConcurrentHashMap<Socket, ConcurrentHashMap<String, List<Socket>>> getLoadAttempts() {
        return loadAttempts;
    }

    public ConcurrentHashMap<String, Integer> getFileToSize() {
        return fileToSize;
    }

}
