/*******************************************************************************
 * Copyright (c) 2013 University of Illinois
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package rvpredict.trace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Stack;
import java.util.List;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Table;

/**
 * Representation of the execution trace. Each event is created as a node with a
 * corresponding type. Events are indexed by their thread Id, Type, and memory
 * address.
 *
 * @author jeffhuang
 *
 */
public class Trace {

    // rawfulltrace represents all the raw events in the global order
    private final List<Event> rawfulltrace = new ArrayList<>();

    // indexed by address, the set of read/write threads
    // used to prune away local data accesses
    private final Map<String, Set<Long>> indexedReadThreads = new HashMap<>();
    private final Map<String, Set<Long>> indexedWriteThreads = new HashMap<>();

    // the set of shared memory locations
    private final Set<String> sharedAddresses = new HashSet<>();
    // the set of threads
    private final Set<Long> threads = new HashSet<>();

    // fulltrace represents all the critical events in the global order
    private final List<Event> fulltrace = new ArrayList<>();

    // per thread node map
    private final Map<Long, List<Event>> threadNodesMap = new HashMap<>();

    // the first node and last node map of each thread
    private final Map<Long, Event> threadFirstNodeMap = new HashMap<>();
    private final Map<Long, Event> threadLastNodeMap = new HashMap<>();

    // per thread per lock lock/unlock pair
    private final Map<Long, Map<Long, List<LockPair>>> threadIndexedLockPairs = new HashMap<>();
    private final Map<Long, Stack<SyncEvent>> threadSyncStack = new HashMap<>();

    // per thread branch nodes
    private final Map<Long, List<BranchNode>> threadBranchNodes = new HashMap<>();

    // per thead synchronization nodes
    private final Map<Long, List<SyncEvent>> syncNodesMap = new HashMap<>();

    /**
     * Read events on each address.
     */
    private final Map<String, List<ReadEvent>> addrToReadEvents = new HashMap<>();

    /**
     * Write events on each address.
     */
    private final Map<String, List<WriteEvent>> addrToWriteEvents = new HashMap<>();

    /**
     * Lists of {@code MemoryAccessEvent}'s indexed by address and thread ID.
     */
    private final Table<String, Long, List<MemoryAccessEvent>> memAccessEventsTbl = HashBasedTable.create();

    /**
     * Initial value of each address.
     */
    private final Map<String, Long> initValues;

    private List<ReadEvent> allReadNodes;

    private final TraceInfo info;

    public Trace(Map<String, Long> initValues, TraceInfo info) {
        assert initValues != null && info != null;
        this.initValues = initValues;
        this.info = info;
    }

    /**
     * return true if sharedAddresses is not empty
     *
     * @return
     */
    public boolean mayRace() {
        return !sharedAddresses.isEmpty();
    }

    public List<Event> getFullTrace() {
        return fulltrace;
    }

    public Long getInitValueOf(String addr) {
        return initValues.get(addr);
    }

    public Map<Integer, String> getLocIdToStmtSigMap() {
        return info.getLocIdToStmtSigMap();
    }

    public Map<Long, Event> getThreadFirstNodeMap() {
        return threadFirstNodeMap;
    }

    public Map<Long, Event> getThreadLastNodeMap() {
        return threadLastNodeMap;
    }

    public List<Event> getThreadEvents(long threadId) {
        return threadNodesMap.get(threadId);
    }

    public Map<Long, List<Event>> getThreadIdToEventsMap() {
        return threadNodesMap;
    }

    public Map<Long, List<SyncEvent>> getSyncNodesMap() {
        return syncNodesMap;
    }

    public List<ReadEvent> getReadEventsOn(String addr) {
        List<ReadEvent> events = addrToReadEvents.get(addr);
        return events == null ? Lists.<ReadEvent>newArrayList() : events;
    }

    public List<WriteEvent> getWriteEventsOn(String addr) {
        List<WriteEvent> events = addrToWriteEvents.get(addr);
        return events == null ? Lists.<WriteEvent>newArrayList() : events;
    }

    public Table<String, Long, List<MemoryAccessEvent>> getMemAccessEventsTable() {
        return memAccessEventsTbl;
    }

    public Map<String, Long> getFinalValues() {
        Map<String, Long> finalValues = new HashMap<>(initValues);
        for (String addr : addrToWriteEvents.keySet()) {
            List<WriteEvent> writeEvents = addrToWriteEvents.get(addr);
            finalValues.put(addr, writeEvents.get(writeEvents.size() - 1).getValue());
        }
        return finalValues;
    }

    public List<ReadEvent> getAllReadNodes() {
        if (allReadNodes == null) {
            allReadNodes = new ArrayList<>();
            Iterator<List<ReadEvent>> it = addrToReadEvents.values().iterator();
            while (it.hasNext()) {
                allReadNodes.addAll(it.next());
            }
        }

        return allReadNodes;
    }

    // get dependent nodes of rnode and wnode
    // if w/o branch information, then all read
    // nodes that happen-before rnode/wnode are
    // considered
    // otherwise, only the read nodes that
    // before the most recent branch nodes
    // before rnode/wnode are considered
    // TODO: NEED to include the dependent nodes from other threads
    public List<ReadEvent> getDependentReadNodes(MemoryAccessEvent rnode, boolean branch) {

        List<ReadEvent> readnodes = new ArrayList<>();
        long tid = rnode.getTID();
        long POS = rnode.getGID() - 1;
        if (branch) {
            long pos = -1;
            List<BranchNode> branchNodes = threadBranchNodes.get(tid);
            if (branchNodes != null)
                // TODO: improve to log(n) complexity
                for (int i = 0; i < branchNodes.size(); i++) {
                    long id = branchNodes.get(i).getGID();
                    if (id > POS)
                        break;
                    else
                        pos = id;
                }
            POS = pos;
        }

        if (POS >= 0) {
            List<Event> nodes = threadNodesMap.get(tid);// TODO:
                                                                 // optimize
                                                                 // here to
                                                                 // check only
                                                                 // READ node
            for (int i = 0; i < nodes.size(); i++) {
                Event node = nodes.get(i);
                if (node.getGID() > POS)
                    break;
                else {
                    if (node instanceof ReadEvent)
                        readnodes.add((ReadEvent) node);
                }
            }
        }

        return readnodes;
    }

    /**
     * add a new event to the trace in the order of its appearance
     *
     * @param node
     */
    public void addRawNode(AbstractEvent node) {
        rawfulltrace.add(node);
        if (node instanceof MemoryAccessEvent) {
            String addr = ((MemoryAccessEvent) node).getAddr();
            Long tid = node.getTID();

            if (node instanceof ReadEvent) {
                Set<Long> set = indexedReadThreads.get(addr);
                if (set == null) {
                    set = new HashSet<Long>();
                    indexedReadThreads.put(addr, set);
                }
                set.add(tid);
            } else {
                Set<Long> set = indexedWriteThreads.get(addr);
                if (set == null) {
                    set = new HashSet<Long>();
                    indexedWriteThreads.put(addr, set);
                }
                set.add(tid);
            }
        }
    }

    /**
     * add a new filtered event to the trace in the order of its appearance
     *
     * @param node
     */
    private void addNode(Event node) {
        Long tid = node.getTID();
        threads.add(tid);

        if (node instanceof BranchNode) {
            // branch node
            info.incrementBranchNumber();

            List<BranchNode> branchnodes = threadBranchNodes.get(tid);
            if (branchnodes == null) {
                branchnodes = new ArrayList<>();
                threadBranchNodes.put(tid, branchnodes);
            }
            branchnodes.add((BranchNode) node);
        } else if (node instanceof InitEvent) {
            // initial write node
            initValues.put(((InitEvent) node).getAddr(), ((InitEvent) node).getValue());
            info.incrementInitWriteNumber();
        } else {
            // all critical nodes -- read/write/synchronization events

            fulltrace.add(node);

            List<Event> threadNodes = threadNodesMap.get(tid);
            if (threadNodes == null) {
                threadNodes = new ArrayList<>();
                threadNodesMap.put(tid, threadNodes);
                threadFirstNodeMap.put(tid, node);

            }

            threadNodes.add(node);

            // TODO: Optimize it -- no need to update it every time
            threadLastNodeMap.put(tid, node);
            if (node instanceof MemoryAccessEvent) {
                info.incrementSharedReadWriteNumber();

                MemoryAccessEvent mnode = (MemoryAccessEvent) node;
                String addr = mnode.getAddr();

                List<MemoryAccessEvent> memAccessEvents = memAccessEventsTbl.get(addr, tid);
                if (memAccessEvents == null) {
                    memAccessEvents = Lists.newArrayList();
                    memAccessEventsTbl.put(addr, tid, memAccessEvents);
                }
                memAccessEvents.add(mnode);

                if (node instanceof ReadEvent) {
                    List<ReadEvent> readNodes = addrToReadEvents.get(addr);
                    if (readNodes == null) {
                        readNodes = new ArrayList<>();
                        addrToReadEvents.put(addr, readNodes);
                    }
                    readNodes.add((ReadEvent) node);

                } else {
                    List<WriteEvent> writeNodes = addrToWriteEvents.get(addr);
                    if (writeNodes == null) {
                        writeNodes = new ArrayList<>();
                        addrToWriteEvents.put(addr, writeNodes);
                    }
                    writeNodes.add((WriteEvent) node);
                }
            } else if (node instanceof SyncEvent) {
                // synchronization nodes
                info.incrementSyncNumber();

                long addr = ((SyncEvent) node).getSyncObject();
                List<SyncEvent> syncNodes = syncNodesMap.get(addr);
                if (syncNodes == null) {
                    syncNodes = new ArrayList<>();
                    syncNodesMap.put(addr, syncNodes);
                }

                syncNodes.add((SyncEvent) node);

                if (node.getType().equals(EventType.LOCK)) {
                    Stack<SyncEvent> stack = threadSyncStack.get(tid);
                    if (stack == null) {
                        stack = new Stack<SyncEvent>();
                        threadSyncStack.put(tid, stack);
                    }

                    stack.push((SyncEvent) node);
                } else if (node.getType().equals(EventType.UNLOCK)) {
                    Map<Long, List<LockPair>> indexedLockpairs = threadIndexedLockPairs
                            .get(tid);
                    if (indexedLockpairs == null) {
                        indexedLockpairs = new HashMap<>();
                        threadIndexedLockPairs.put(tid, indexedLockpairs);
                    }
                    List<LockPair> lockpairs = indexedLockpairs.get(addr);
                    if (lockpairs == null) {
                        lockpairs = new ArrayList<>();
                        indexedLockpairs.put(addr, lockpairs);
                    }

                    Stack<SyncEvent> stack = threadSyncStack.get(tid);
                    if (stack == null) {
                        stack = new Stack<SyncEvent>();
                        threadSyncStack.put(tid, stack);
                    }
                    // assert(stack.size()>0); //this is possible when segmented
                    if (stack.size() == 0)
                        lockpairs.add(new LockPair(null, (SyncEvent) node));
                    else if (stack.size() == 1)
                        lockpairs.add(new LockPair(stack.pop(), (SyncEvent) node));
                    else
                        stack.pop();// handle reentrant lock
                }
            }
        }
    }

    /**
     * once trace is completely loaded, do two things: 1. prune away local data
     * accesses 2. process the remaining trace
     */
    public void finishedLoading() {
        HashSet<String> addrs = new HashSet<String>();
        addrs.addAll(indexedReadThreads.keySet());
        addrs.addAll(indexedWriteThreads.keySet());
        for (Iterator<String> addrIt = addrs.iterator(); addrIt.hasNext();) {
            String addr = addrIt.next();
            Set<Long> wtids = indexedWriteThreads.get(addr);
            if (wtids != null && wtids.size() > 0) {
                if (wtids.size() > 1) {
                    sharedAddresses.add(addr);

                } else {
                    Set<Long> rtids = indexedReadThreads.get(addr);
                    if (rtids != null) {
                        HashSet<Long> set = new HashSet<>(rtids);
                        set.addAll(wtids);
                        if (set.size() > 1)
                            sharedAddresses.add(addr);
                    }
                }
            }
        }

        // add trace
        for (int i = 0; i < rawfulltrace.size(); i++) {
            Event node = rawfulltrace.get(i);
            if (node instanceof MemoryAccessEvent) {
                String addr = ((MemoryAccessEvent) node).getAddr();
                if (sharedAddresses.contains(addr))
                    addNode(node);
                else
                    info.incrementLocalReadWriteNumber();

            } else
                addNode(node);
        }

        // process sync stack to handle windowing
        checkSyncStack();

        // clear rawfulltrace
        rawfulltrace.clear();

        // add info
        info.addSharedAddresses(sharedAddresses);
        info.addThreads(threads);

    }

    /**
     * compute the lock/unlock pairs because we analyze the trace window by
     * window, lock/unlock may not be in the same window, so we may have null
     * lock or unlock node in the pair.
     */
    private void checkSyncStack() {
        // check threadSyncStack - only to handle when segmented
        Iterator<Entry<Long, Stack<SyncEvent>>> entryIt = threadSyncStack.entrySet().iterator();
        while (entryIt.hasNext()) {
            Entry<Long, Stack<SyncEvent>> entry = entryIt.next();
            Long tid = entry.getKey();
            Stack<SyncEvent> stack = entry.getValue();

            if (!stack.isEmpty()) {
                Map<Long, List<LockPair>> indexedLockpairs = threadIndexedLockPairs
                        .get(tid);
                if (indexedLockpairs == null) {
                    indexedLockpairs = new HashMap<>();
                    threadIndexedLockPairs.put(tid, indexedLockpairs);
                }

                while (!stack.isEmpty()) {
                    SyncEvent syncnode = stack.pop();// lock or wait

                    List<LockPair> lockpairs = indexedLockpairs.get(syncnode.getSyncObject());
                    if (lockpairs == null) {
                        lockpairs = new ArrayList<>();
                        indexedLockpairs.put(syncnode.getSyncObject(), lockpairs);
                    }

                    lockpairs.add(new LockPair(syncnode, null));
                }
            }
        }
    }

    // TODO(YilongL): add javadoc; addr seems to be some abstract address, e.g.
    // "_.1", built when reading the trace; figure out what happens and improve it
    public boolean isVolatileAddr(String addr) {
        // all field addr should contain ".", not true for array access
        int dotPos = addr.indexOf(".");
        return dotPos != -1 && info.isVolatileAddr(Integer.valueOf(addr.substring(dotPos + 1)));
    }

}
