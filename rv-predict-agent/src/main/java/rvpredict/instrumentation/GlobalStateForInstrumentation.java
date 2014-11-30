package rvpredict.instrumentation;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class GlobalStateForInstrumentation {
    public static GlobalStateForInstrumentation instance = new GlobalStateForInstrumentation();

    // can be computed during offline analysis

    /**
     * YilongL: Those fields starting with `unsaved` are used for incremental
     * saving of metadata. They are mostly not concurrent data-structures and,
     * thus, synchronize with their main data-structure counterparts, except for
     * {@code unsavedThreadIdToName} because we assume thread Id to be unique in
     * our case.
     */

    public final ConcurrentHashMap<Long, String> threadIdToName = new ConcurrentHashMap<>();

    public final ConcurrentHashMap<String, Integer> varSigToId = new ConcurrentHashMap<>();

    public final ConcurrentHashMap<String, Integer> stmtSigToLocId = new ConcurrentHashMap<>();
    public final List<Map.Entry<String, Integer>> unsavedStmtSigToLocId = new ArrayList<>();

    public final Set<String> volatileVariables = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    public final List<String> unsavedVolatileVariables = new ArrayList<>();

    public void registerThreadName(long tid, String name) {
        threadIdToName.put(tid, name);
    }

    public int getVariableId(String sig) {
        /* YilongL: the following double-checked locking is correct because
         * varSigToId is a ConcurrentHashMap */
        Integer variableId = varSigToId.get(sig);
        if (variableId == null) {
            synchronized (varSigToId) {
                variableId = varSigToId.get(sig);
                if (variableId == null) {
                    variableId = varSigToId.size() + 1;
                    varSigToId.put(sig, variableId);
                }
            }
        }

        return variableId;
    }

    public void addVolatileVariable(String sig) {
        if (!volatileVariables.contains(sig)) {
            synchronized (volatileVariables) {
                if (!volatileVariables.contains(sig)) {
                    volatileVariables.add(sig);
                    unsavedVolatileVariables.add(sig);
                }
            }
        }
    }

    public int getLocationId(String sig) {
        Integer locId = stmtSigToLocId.get(sig);
        if (locId == null) {
            synchronized (stmtSigToLocId) {
                locId = stmtSigToLocId.get(sig);
                if (locId == null) {
                    locId = stmtSigToLocId.size() + 1;
                    stmtSigToLocId.put(sig, locId);
                    unsavedStmtSigToLocId.add(new SimpleEntry<>(sig, locId));
                }
            }
        }

        return locId;
    }
}
