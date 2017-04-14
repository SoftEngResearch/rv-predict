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
package com.runtimeverification.rvpredict.smt;

import static com.runtimeverification.rvpredict.smt.formula.FormulaTerm.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.microsoft.z3.FuncDecl;
import com.microsoft.z3.Model;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import com.runtimeverification.rvpredict.config.Configuration;
import com.runtimeverification.rvpredict.log.ReadonlyEvent;
import com.runtimeverification.rvpredict.smt.formula.BoolFormula;
import com.runtimeverification.rvpredict.smt.formula.BooleanConstant;
import com.runtimeverification.rvpredict.smt.formula.ConcretePhiVariable;
import com.runtimeverification.rvpredict.smt.formula.FormulaTerm;
import com.runtimeverification.rvpredict.smt.formula.IntConstant;
import com.runtimeverification.rvpredict.smt.formula.OrderVariable;
import com.runtimeverification.rvpredict.smt.visitors.Z3Filter;
import com.runtimeverification.rvpredict.trace.LockRegion;
import com.runtimeverification.rvpredict.trace.MemoryAccessBlock;
import com.runtimeverification.rvpredict.trace.Trace;
import com.runtimeverification.rvpredict.violation.Race;

public class MaximalCausalModel {

    private final Trace trace;

    /**
     * Keeps track of the must-happen-before (MHB) relations in paper.
     * <p>
     * The must-happen-before (MHB) relations form a special DAG where only a
     * few nodes have more than one outgoing edge. To speed up the reachability
     * query between two nodes, we first collapsed the DAG as much as possible.
     */
    private TransitiveClosure mhbClosure;

    private final LockSetEngine locksetEngine = new LockSetEngine();

    /**
     * Map from read events to the corresponding concrete feasibility formulas.
     */
    private final Map<ReadonlyEvent, BoolFormula> readToPhiConc = new HashMap<>();

    /**
     * The formula that describes the maximal causal model of the trace tau.
     */
    private final FormulaTerm.Builder phiTau = FormulaTerm.andBuilder();

    private final Map<String, ReadonlyEvent> nameToEvent = new HashMap<>();

    private final Z3Filter z3filter;

    private final com.microsoft.z3.Solver solver;

    public static MaximalCausalModel create(Trace trace, Z3Filter z3filter, Solver solver) {
        MaximalCausalModel model = new MaximalCausalModel(trace, z3filter, solver);
        model.addPhiMHB();
        model.addPhiLock();
        return model;
    }

    private MaximalCausalModel(Trace trace, Z3Filter z3filter, Solver solver) {
        this.trace = trace;
        this.z3filter = z3filter;
        this.solver = solver;
    }

    private BoolFormula HB(ReadonlyEvent event1, ReadonlyEvent event2) {
        return LESS_THAN(OrderVariable.get(event1), OrderVariable.get(event2));
    }

    private BoolFormula HB(LockRegion lockRegion1, LockRegion lockRegion2) {
        ReadonlyEvent unlock = lockRegion1.getUnlock();
        ReadonlyEvent lock = lockRegion2.getLock();
        return (unlock == null || lock == null) ? BooleanConstant.FALSE : HB(unlock, lock);
    }

    private BoolFormula MUTEX(LockRegion lockRegion1, LockRegion lockRegion2) {
        return OR(HB(lockRegion1, lockRegion2), HB(lockRegion2, lockRegion1));
    }

    /**
     * Adds must-happen-before (MHB) constraints.
     */
    private void addPhiMHB() {
        TransitiveClosure.Builder mhbClosureBuilder = TransitiveClosure.builder(trace.getSize());

        /* build intra-thread program order constraint */
        trace.eventsByThreadID().forEach((tid, events) -> {
            mhbClosureBuilder.createNewGroup(events.get(0));
            events.forEach(event -> nameToEvent.put(OrderVariable.get(event).toString(), event));
            for (int i = 1; i < events.size(); i++) {
                ReadonlyEvent e1 = events.get(i - 1);
                ReadonlyEvent e2 = events.get(i);
                phiTau.add(HB(e1, e2));
                /* every group should start with a join event and end with a start event */
                if (e1.isStart() || e2.isJoin()) {
                    mhbClosureBuilder.createNewGroup(e2);
                    mhbClosureBuilder.addRelation(e1, e2);
                } else {
                    mhbClosureBuilder.addToGroup(e2, e1);
                }
            }
        });

        /* build inter-thread synchronization constraint */
        trace.getInterThreadSyncEvents().forEach(event -> {
            if (event.isStart()) {
                ReadonlyEvent fst = trace.getFirstEvent(event.getSyncObject());
                if (fst != null) {
                    phiTau.add(HB(event, fst));
                    mhbClosureBuilder.addRelation(event, fst);
                }
            } else if (event.isJoin()) {
                ReadonlyEvent last = trace.getLastEvent(event.getSyncObject());
                if (last != null) {
                    phiTau.add(HB(last, event));
                    mhbClosureBuilder.addRelation(last, event);
                }
            }
        });

        mhbClosure = mhbClosureBuilder.build();
    }

    /**
     * Adds lock mutual exclusion constraints.
     */
    private void addPhiLock() {
        trace.getLockIdToLockRegions().forEach((lockId, lockRegions) -> {
            lockRegions.forEach(locksetEngine::add);

            /* assert lock regions mutual exclusion */
            lockRegions.forEach(lr1 -> {
                lockRegions.forEach(lr2 -> {
                    if (lr1.getTID() < lr2.getTID()
                            && (lr1.isWriteLocked() || lr2.isWriteLocked())) {
                        phiTau.add(MUTEX(lr1, lr2));
                    }
                });
            });
        });
    }

    private BoolFormula getPhiConc(MemoryAccessBlock block) {
        ReadonlyEvent read = block.getFirstRead();
        if (read == null) {
            return getPhiAbs(block);
        } else {
            if (!readToPhiConc.containsKey(read)) {
                readToPhiConc.put(read, null);
                readToPhiConc.put(read, AND(getPhiAbs(block), getPhiSC(read)));
            }
            return new ConcretePhiVariable(read);
        }
    }

    private BoolFormula getPhiAbs(MemoryAccessBlock block) {
        return block.prev() != null ? getPhiConc(block.prev()) : BooleanConstant.TRUE;
    }

    private BoolFormula getPhiSC(ReadonlyEvent read) {
        /* compute all the write events that could interfere with the read event */
        List<ReadonlyEvent> diffThreadSameAddrSameValWrites = new ArrayList<>();
        List<ReadonlyEvent> diffThreadSameAddrDiffValWrites = new ArrayList<>();
        trace.getWriteEvents(read.getDataAddress()).forEach(write -> {
            if (write.getThreadId() != read.getThreadId() && !happensBefore(read, write)) {
                if (write.getDataValue() == read.getDataValue()) {
                    diffThreadSameAddrSameValWrites.add(write);
                } else {
                    diffThreadSameAddrDiffValWrites.add(write);
                }
            }
        });

        ReadonlyEvent sameThreadPrevWrite = trace.getSameThreadPrevWrite(read);
        if (sameThreadPrevWrite != null) {
            /* sameThreadPrevWrite is available in the current window */
            if (read.getDataValue() == sameThreadPrevWrite.getDataValue()) {
                /* the read value is the same as sameThreadPrevWrite */
                FormulaTerm.Builder or = FormulaTerm.orBuilder();

                { /* case 1: read the value written in the same thread */
                    FormulaTerm.Builder and = FormulaTerm.andBuilder();
                    diffThreadSameAddrDiffValWrites
                            .forEach(w -> and.add(OR(HB(w, sameThreadPrevWrite), HB(read, w))));
                    or.add(and.build());
                }

                /* case 2: read the value written in another thread  */
                diffThreadSameAddrSameValWrites.forEach(w1 -> {
                    if (!happensBefore(w1, sameThreadPrevWrite)) {
                        FormulaTerm.Builder and = FormulaTerm.andBuilder();
                        and.add(getPhiAbs(trace.getMemoryAccessBlock(w1)));
                        diffThreadSameAddrDiffValWrites.forEach(w2 -> {
                            if (!happensBefore(w2, w1) && !happensBefore(w2, sameThreadPrevWrite)) {
                                and.add(OR(HB(w2, w1), HB(read, w2)));
                            }
                        });
                        or.add(and.build());
                    }
                });
                return or.build();
            } else {
                /* the read value is different from sameThreadPrevWrite */
                if (!diffThreadSameAddrSameValWrites.isEmpty()) {
                    FormulaTerm.Builder or = FormulaTerm.orBuilder();
                    diffThreadSameAddrSameValWrites.forEach(w1 -> {
                        FormulaTerm.Builder and = FormulaTerm.andBuilder();
                        and.add(getPhiAbs(trace.getMemoryAccessBlock(w1)));
                        and.add(HB(sameThreadPrevWrite, w1), HB(w1, read));
                        diffThreadSameAddrDiffValWrites.forEach(w2 -> {
                            if (!happensBefore(w2, w1)) {
                                and.add(OR(HB(w2, w1), HB(read, w2)));
                            }
                        });
                        or.add(and.build());
                    });
                    return or.build();
                } else {
                    /* the read-write consistency constraint is UNSAT */
                    trace.logger().debug("Missing write events on " + read.getDataAddress());
                    return BooleanConstant.TRUE;
                }
            }
        } else {
            /* sameThreadPrevWrite is unavailable in the current window */
            ReadonlyEvent diffThreadPrevWrite = trace.getAllThreadsPrevWrite(read);
            if (diffThreadPrevWrite == null) {
                /* the initial value of this address must be read.getDataValue() */
                FormulaTerm.Builder and = FormulaTerm.andBuilder();
                diffThreadSameAddrDiffValWrites.forEach(w -> and.add(HB(read, w)));
                return and.build();
            } else {
                /* the initial value of this address is unknown */
                if (!diffThreadSameAddrSameValWrites.isEmpty()) {
                    FormulaTerm.Builder or = FormulaTerm.orBuilder();
                    diffThreadSameAddrSameValWrites.forEach(w1 -> {
                        FormulaTerm.Builder and = FormulaTerm.andBuilder();
                        and.add(getPhiAbs(trace.getMemoryAccessBlock(w1)));
                        and.add(HB(w1, read));
                        diffThreadSameAddrDiffValWrites.forEach(w2 -> {
                            if (!happensBefore(w2, w1)) {
                                and.add(OR(HB(w2, w1), HB(read, w2)));
                            }
                        });
                        or.add(and.build());
                    });
                    return or.build();
                } else {
                    /* the read-write consistency constraint is UNSAT */
                    trace.logger().debug("Missing write events on " + read.getDataAddress());
                    return BooleanConstant.TRUE;
                }
            }
        }
    }

    /**
     * Checks if one event happens before another.
     */
    private boolean happensBefore(ReadonlyEvent e1, ReadonlyEvent e2) {
        return mhbClosure.inRelation(e1, e2);
    }

    private boolean failPecanCheck(Race race) {
        ReadonlyEvent e1 = race.firstEvent();
        ReadonlyEvent e2 = race.secondEvent();
        return locksetEngine.hasCommonLock(e1, e2) || happensBefore(e1, e2)
                || happensBefore(e2, e1);
    }

    private BoolFormula getRaceAssertion(Race race) {
        ReadonlyEvent e1 = race.firstEvent();
        ReadonlyEvent e2 = race.secondEvent();
        FormulaTerm.Builder raceAsst = FormulaTerm.andBuilder();
        raceAsst.add(INT_EQUAL(OrderVariable.get(e1), OrderVariable.get(e2)),
                getPhiAbs(trace.getMemoryAccessBlock(e1)),
                getPhiAbs(trace.getMemoryAccessBlock(e2)));
        return raceAsst.build();
    }

    private class EventWithOrder {
        private final ReadonlyEvent event;
        private final long orderId;
        public EventWithOrder(ReadonlyEvent event, long orderId) {
            this.event = event;
            this.orderId = orderId;
        }
        public ReadonlyEvent getEvent() {
            return event;
        }
        public long getOrderId() {
            return orderId;
        }
    }

    /**
     * Checks if the given race suspects are real. Race suspects are grouped by
     * their signatures.
     *
     * @param sigToRaceSuspects
     * @return a map from race signatures to real race instances
     */
    public Map<String, Race> checkRaceSuspects(Map<String, List<Race>> sigToRaceSuspects) {
        /* specialize the maximal causal model based on race queries */
        Map<Race, BoolFormula> suspectToAsst = new HashMap<>();
        sigToRaceSuspects.values().forEach(suspects -> {
            suspects.removeIf(this::failPecanCheck);
            suspects.forEach(p -> suspectToAsst.computeIfAbsent(p, this::getRaceAssertion));
        });
        sigToRaceSuspects.entrySet().removeIf(e -> e.getValue().isEmpty());
        if (sigToRaceSuspects.isEmpty()) {
            return Collections.emptyMap();
        }

//        trace.logger().debug().println("start analyzing: " + trace.getBaseGID());
//        sigToRaceSuspects.forEach((sig, l) -> trace.logger().debug().println(sig + ": " + l.size()));

        Map<String, Race> result = new HashMap<>();
        try {
            solver.push();
            /* translate our formula into Z3 AST format */
            solver.add(z3filter.filter(phiTau.build()));
            for (Map.Entry<ReadonlyEvent, BoolFormula> entry : readToPhiConc.entrySet()) {
                solver.add(z3filter.filter(BOOL_EQUAL(new ConcretePhiVariable(entry.getKey()),
                        entry.getValue())));
            }
//            checkTraceConsistency(z3filter, solver);

            if (Configuration.debug) {
                findAndDumpOrdering();
            }
            /* check race suspects */

            for (Map.Entry<String, List<Race>> entry : sigToRaceSuspects.entrySet()) {
                for (Race race : entry.getValue()) {
                    solver.push();
                    solver.add(z3filter.filter(suspectToAsst.get(race)));
                    boolean isRace = solver.check() == Status.SATISFIABLE;
                    solver.pop();
                    if (isRace) {
                        result.put(entry.getKey(), race);
                        break;
                    }
                }
            }
            solver.pop();
            z3filter.clear();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return result;
    }

    private void findAndDumpOrdering() {
        solver.push();
        if (solver.check() == Status.SATISFIABLE) {
            Model model = solver.getModel();
            Map<Long, List<EventWithOrder>> threadToExecution = new HashMap<>();
            for (FuncDecl f : model.getConstDecls()) {
                String name = f.getName().toString();
                if (nameToEvent.containsKey(name)) {
                    ReadonlyEvent event = nameToEvent.get(name);
                    EventWithOrder eventWithOrder =
                            new EventWithOrder(event, Long.parseLong(model.getConstInterp(f).toString()));
                    threadToExecution.computeIfAbsent(event.getThreadId(), a -> new ArrayList<>()).add(eventWithOrder);
                }
            }
            threadToExecution.values().forEach(events ->
                    events.sort(Comparator.comparingLong(e -> e.getOrderId())));

            System.out.println("Possible ordering of events, per thread ..........");
            threadToExecution.forEach((tid, events) -> {
                ArrayList<String> description = new ArrayList<>();
                events.forEach(e -> description.add(e.getEvent().getEventId() + ":" + e.getOrderId()));
                System.out.print("  Thread:" + tid);
                System.out.print(" -> ");
                System.out.println(String.join(" ", description));
            });
            System.out.println(".......... That's all folks!");
        }
        solver.pop();
    }

    /**
     * Checks if the logged trace is in a consistent state.
     */
    @SuppressWarnings("unused")
    private void checkTraceConsistency(Z3Filter z3filter, com.microsoft.z3.Solver solver)
            throws Exception {
        List<MemoryAccessBlock> blks = new ArrayList<>();
        trace.memoryAccessBlocksByThreadID().values().forEach(l -> blks.addAll(l));
        Collections.sort(blks);

        solver.push();
        /* simply assign the GID of an event to its order variable */
        for (List<ReadonlyEvent> l : trace.eventsByThreadID().values()) {
            for (ReadonlyEvent event : l) {
                solver.add(z3filter.filter(
                        INT_EQUAL(OrderVariable.get(event), new IntConstant(event.getEventId()))));
            }
        }

        /* assert that all events should be concretely feasible */
        for (MemoryAccessBlock blk : blks) {
            solver.add(z3filter.filter(getPhiConc(blk)));
        }

        if (solver.check() != Status.SATISFIABLE) {
            throw new RuntimeException("Inconsistent trace!");
        }
        solver.pop();
    }

}
