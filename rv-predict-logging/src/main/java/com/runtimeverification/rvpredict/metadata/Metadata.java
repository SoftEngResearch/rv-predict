package com.runtimeverification.rvpredict.metadata;

import java.io.Serializable;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("serial")
public class Metadata implements Serializable {

    public static final int MAX_NUM_OF_VARIABLES = 1024 * 128;

    // this should be enough for more than 1 million lines of code
    public static final int MAX_NUM_OF_LOCATIONS = 1024 * 1024;

    private transient final AtomicInteger nextVarId = new AtomicInteger(1);

    private transient final AtomicInteger nextLocId = new AtomicInteger(1);

    private transient final ConcurrentHashMap<String, Integer> varSigToVarId = new ConcurrentHashMap<>();

    private transient final ConcurrentHashMap<String, Integer> locSigToLocId = new ConcurrentHashMap<>();

    private final String[] varIdToVarSig = new String[MAX_NUM_OF_VARIABLES];

    private final String[] locIdToLocSig = new String[MAX_NUM_OF_LOCATIONS];

    private final Set<Integer> volatileVarIds = Collections
            .newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    private static final Metadata instance = new Metadata();

    /**
     * Note: This method should be used only in a few places and definitely NOT
     * in offline prediction which should get its {@code Metadata} instance from
     * deserialization.
     *
     * @return a singleton instance of {@code Metadata}.
     */
    public static Metadata singleton() {
        return instance;
    }

    private Metadata() { }

    public int getVariableId(String cname, String fname) {
        String varSig = cname + "." + fname;
        Integer varId = varSigToVarId.get(varSig);
        if (varId == null) {
            varSigToVarId.putIfAbsent(varSig, nextVarId.getAndIncrement());
            varId = varSigToVarId.get(varSig);
            varIdToVarSig[varId] = varSig;
        }
        return varId;
    }

    public String getVariableSig(int varId) {
        return varIdToVarSig[varId];
    }

    public int getLocationId(String locSig) {
        Integer locId = locSigToLocId.get(locSig);
        if (locId == null) {
            locSigToLocId.putIfAbsent(locSig, nextLocId.getAndIncrement());
            locId = locSigToLocId.get(locSig);
            locIdToLocSig[locId] = locSig;
        }
        return locId;
    }

    public String getLocationSig(int locId) {
        return locIdToLocSig[locId];
    }

    public void addVolatileVariable(String cname, String fname) {
        volatileVarIds.add(getVariableId(cname, fname));
    }

    public boolean isVolatile(int varId) {
        return volatileVarIds.contains(varId);
    }

}
