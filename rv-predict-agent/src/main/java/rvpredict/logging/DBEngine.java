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
package rvpredict.logging;

import rvpredict.db.EventItem;
import rvpredict.config.Config;
import rvpredict.db.EventOutputStream;
import rvpredict.instrumentation.GlobalStateForInstrumentation;
import rvpredict.trace.EventType;

import java.io.*;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Class encapsulating functionality for recording events to disk.
 * TODO(TraianSF): Maybe we should rename the class now that there is no DB code left.
 *
 * @author TraianSF
 *
 */
public class DBEngine {

    private final AtomicLong globalEventID  = new AtomicLong(0);
    private static final int BUFFER_THRESHOLD = 10000;
    private final GlobalStateForInstrumentation globalState;
    private final Thread metadataLoggingThread;
    private final ThreadLocalEventStream threadLocalTraceOS;
    private final ObjectOutputStream metadataOS;
    private boolean shutdown = false;

    /**
     * Method invoked at the end of the logging task, to insure that
     * all data is flushed to disk before concluding.
     */
    public void finishLogging() {
        shutdown = true;
        try {
            synchronized (metadataOS) {
                metadataOS.notify();
            }
            metadataLoggingThread.join();
            for (EventOutputStream stream : threadLocalTraceOS.getStreamsMap().values()) {
                try {
                    stream.flush();
                } catch (IOException e) {
                    // TODO(TraianSF) We can probably safely ignore file errors at this (shutdown) stage
                    e.printStackTrace();
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        try {
            metadataOS.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public DBEngine(GlobalStateForInstrumentation globalState, String directory) {
        this.globalState = globalState;
        threadLocalTraceOS = new ThreadLocalEventStream(directory);
        metadataOS = createMetadataOS(directory);
        metadataLoggingThread = startMetadataLogging();
    }

    private Thread startMetadataLogging() {
        Thread metadataLoggingThread = new Thread(new Runnable() {

            @Override
            public void run() {

                while (!shutdown) {
                    try {
                        synchronized (metadataOS) {
                            metadataOS.wait(60000);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    saveMetaData();
                }
            }

        });

        metadataLoggingThread.setDaemon(true);

        metadataLoggingThread.start();
        return metadataLoggingThread;
    }

    private ObjectOutputStream createMetadataOS(String directory) {
        try {
            return new ObjectOutputStream(
                    new BufferedOutputStream(
                            new FileOutputStream(Paths.get(directory, rvpredict.db.DBEngine.METADATA_BIN).toFile())));
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Saves an {@link rvpredict.db.EventItem} to the database.
     * Each event is saved in a file corresponding to its own thread.
     *
     * @see rvpredict.db.EventItem#EventItem(long, long, int, long, long, long, rvpredict.trace.EventType)
     *      for a more elaborate description of the parameters.
     * @see java.lang.ThreadLocal
     *
     * @param eventType  type of event being recorded
     * @param id location id of the event
     * @param addrl  additional information identifying the event
     * @param addrr additional information identifying the event
     * @param value data involved in the event
     */
    public void saveEvent(EventType eventType, int id, long addrl, long addrr, long value) {
        if (shutdown) return;
        long tid = Thread.currentThread().getId();
        long gid = globalEventID.incrementAndGet();
        EventItem e = new EventItem(gid, tid, id, addrl, addrr, value, eventType);
        try {
            EventOutputStream traceOS = threadLocalTraceOS.get();
            traceOS.writeEvent(e);
            long eventsWritten = traceOS.getEventsWrittenCount();
            if (eventsWritten % BUFFER_THRESHOLD == 0) {
                // Flushing events and metadata periodically to allow crash recovery.
                traceOS.flush();
                synchronized (metadataLoggingThread) {
                    metadataLoggingThread.notify();
                }
            }
        } catch (FileNotFoundException e1) {
            e1.printStackTrace();
        } catch (IOException e1) {
                e1.printStackTrace();
            }
    }

    /**
     * Wrapper for {@link #saveEvent(rvpredict.trace.EventType, int, long, long, long)}
     * The missing arguments default to 0.
     */
    public void saveEvent(EventType eventType, int locId, long arg) {
        saveEvent(eventType, locId, arg, 0, 0);
    }

    /**
     * Wrapper for {@link #saveEvent(rvpredict.trace.EventType, int, long, long, long)}
     * The missing arguments default to 0.
     */
     public void saveEvent(EventType eventType, int locId) {
        saveEvent(eventType, locId, 0, 0, 0);
    }

    private void saveObject(Object threadTidList) {
        try {
            metadataOS.writeObject(threadTidList);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Flush un-previously-saved metadata to disk.
     */
    public void saveMetaData() {
        /* save <volatileVariable, Id> pairs */
        synchronized (globalState.volatileVariables) {
            // TODO(YilongL): volatileVariable Id should be constructed when
            // reading metadata in backend; not here
            List<Entry<String, Integer>> volatileVarIdPairs = new ArrayList<>(globalState.unsavedVolatileVariables.size());
            for (String var : globalState.unsavedVolatileVariables) {
                volatileVarIdPairs.add(new SimpleEntry<>(var, globalState.varSigToId.get(var)));
            }
            saveObject(volatileVarIdPairs);
        }

        /* save <StmtSig, LocId> pairs */
        synchronized (globalState.stmtSigToLocId) {
            saveObject(globalState.unsavedStmtSigToLocId);
            globalState.unsavedStmtSigToLocId.clear();
        }
    }
}
