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
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;
import java.util.Map.Entry;

import config.Configuration;

import trace.AbstractNode;
import trace.IMemNode;
import trace.LockPair;
import trace.ReadNode;
import trace.Trace;
import trace.WriteNode;
import trace.AbstractNode.TYPE;
import violation.AtomicityViolation;
import violation.Deadlock;
import violation.IViolation;
import violation.Race;
import z3.YicesEngineSMTLIB1;
import z3.Z3Engine;
import db.DBEngine;

/**
 * The NewRVPredict class implements our new race detection algorithm based on 
 * constraint solving. The events in the trace are loaded and processed window 
 * by window with a configurable window size. 
 * 
 * @author jeffhuang
 *
 */
public class NewRVPredict {

	private static HashSet<IViolation> violations= new HashSet<IViolation>();
	private static HashSet<IViolation> potentialviolations= new HashSet<IViolation>();
	private static PrintWriter out;
	private static Configuration config;
	private static boolean detectRace = true;
	private static boolean detectAtomicityViolation = false;
	private static boolean detectDeadlock = false;
	
	/**
	 * Initialize the file printer. All race detection statistics are stored
	 * into the file result."window_size".
	 * 
	 * @param appname
	 */
	private static void initPrinter(String appname)
	{
		try{
		String fname = "result."+(config.window_size/1000)+"k";
		out = new PrintWriter(new FileWriter(fname,true));
		
		String type = "";
		if(config.rmm_pso)
			type+="pso: ";
		
		if(config.nobranch)
			type += "maximal: ";
		else if(config.allconsistent)
			type += "Said et al.: ";
		else
			type += "maximal-branch: ";
		out.println("\n------------------ "+type+appname+" -------------------\n");
		}catch(Exception e)
		{
			e.printStackTrace();
		}
	}
	private static void closePrinter()
	{
		if(out!=null)
			out.close();
	}
	private static void report(String msg, boolean isRealRace)
	{
		out.println(msg);
		
		if(isRealRace)
		{
			System.err.println(msg);
		}
		else
			System.out.println(msg);
	}
	
	/**
	 * Deadlock detection method. Not used in race detection.
	 * @param engine
	 * @param trace
	 * @param schedule_prefix
	 */
	private static void detectDeadlock(Z3Engine engine,Trace trace,Vector<String> schedule_prefix)
	{
		HashMap<Long,HashMap<String,Vector<LockPair>>> threadIndexedLockPairs 
			= trace.getThreadIndexedLockPairs();
		Object[] threads = threadIndexedLockPairs.keySet().toArray();
		for(int i=0;i<threads.length-1;i++)
		{
			Set<String> lockset1 = threadIndexedLockPairs.get(threads[i]).keySet();
			if(lockset1.size()>1)
			for(int j=1;j<threads.length;j++)
			{
				Set<String> lockset2 = new HashSet(threadIndexedLockPairs.get(threads[j]).keySet());
				lockset2.retainAll(lockset1);
				if(lockset2.size()>1)
				{
				HashMap<String,Vector<LockPair>> indexedLockpairs1 = threadIndexedLockPairs.get(threads[i]);
				HashMap<String,Vector<LockPair>> indexedLockpairs2 = threadIndexedLockPairs.get(threads[j]);
				Object[] addrs = lockset2.toArray();
				for(int k1=0;k1<addrs.length-1;k1++)
				{
				Vector<LockPair> vlp1a = indexedLockpairs1.get(addrs[k1]);
				Vector<LockPair> vlp2a = indexedLockpairs2.get(addrs[k1]);
				
				for(int k2=1;k2<addrs.length;k2++)
				{
				Vector<LockPair> vlp1b = indexedLockpairs1.get(addrs[k2]);
				Vector<LockPair> vlp2b = indexedLockpairs2.get(addrs[k2]);
				
				for(int i1=0;i1<vlp1a.size();i1++)
				{
				 LockPair lp1a = vlp1a.get(i1);
				 for(int i2=0;i2<vlp2a.size();i2++)
				 {
					 LockPair lp2a = vlp2a.get(i2);
					 for(int i3=0;i3<vlp1b.size();i3++)
					 {
						 LockPair lp1b = vlp1b.get(i3);
						 for(int i4=0;i4<vlp2b.size();i4++)
						 {
							 LockPair lp2b = vlp2b.get(i4);
							 
							 if(lp1a.lock==null||
									 (lp1b.lock!=null&&lp1a.lock.getGID()>lp1b.lock.getGID()))
							 {
								 LockPair templp = lp1b;
								 lp1b = lp1a;
								 lp1a = templp;
							 }
							 
							 if(lp2b.lock==null||
									 (lp2a.lock!=null&&lp2b.lock.getGID()>lp2a.lock.getGID()))
							 {
								 LockPair templp = lp2a;
								 lp2a = lp2b;
								 lp2b = templp;
							 }

							 Deadlock deadlock = new Deadlock(trace.getStmtSigIdMap().get(lp1a.lock.getID()),
																trace.getStmtSigIdMap().get(lp2b.lock.getID()),
																trace.getStmtSigIdMap().get(lp1b.lock.getID()),
																trace.getStmtSigIdMap().get(lp2a.lock.getID()));

							 if(!violations.contains(deadlock))
							 {
								 if(engine.isDeadlock(lp1a,lp2b,lp1b,lp2a))
								 {
										Vector<String> schedule = engine.getSchedule(lp1b.lock.getGID(),trace.getNodeGIDTIdMap(),trace.getThreadIdNameMap());
														
										schedule.addAll(0, schedule_prefix);

										deadlock.addSchedule(schedule);
										
										violations.add(deadlock);
					
										report("Deadlock : "+deadlock,true);
					
										report("Schedule: "+trim(schedule)+"\n",true);
								}
							}
				
						 }
					 }
				 }
				}
				
				}
				
				}
				
				}
			}
		}
	}
	/**
	 * Trim the schedule to show the last 100 only entries
	 * 
	 * @param schedule
	 * @return
	 */
	private static Vector<String> trim(Vector<String> schedule)
	{
		if(schedule.size()>100)
		{
			Vector<String> s = new Vector<String>();
			s.add("...");
			for(int i=schedule.size()-100;i<schedule.size();i++)
				s.add(schedule.get(i));
			return s;
		}
		else
			return schedule;
	}
	/**
	 * Race detection method. For every pair of conflicting data accesses, 
	 * the corresponding race constraint is generated and solved by a solver.
	 * If the solver returns a solution, we report a real data race and 
	 * generate a racy schedule. Otherwise, a potential race is reported.
	 * We call it a potential race but not a false race because it might be
	 * a real data race in another trace.
	 * 
	 * @param engine
	 * @param trace
	 * @param schedule_prefix
	 */
	private static void detectRace(Z3Engine engine, Trace trace, Vector<String> schedule_prefix)
	{
		Iterator<String> 
		addrIt =trace.getIndexedThreadReadWriteNodes().keySet().iterator();
		while(addrIt.hasNext())
		{
			//the dynamic memory location
			String addr = addrIt.next();
			
			//get all read nodes on the address
			Vector<ReadNode> readnodes = trace.getIndexedReadNodes().get(addr);
			
			//get all write nodes on the address
			Vector<WriteNode> writenodes = trace.getIndexedWriteNodes().get(addr);
			
			//skip if there is no write events to the address
			if(writenodes==null||writenodes.size()<1)
			continue;
						
			//check read-write conflict
			if(readnodes!=null)
			for(int i=0;i<readnodes.size();i++)
			{
				ReadNode rnode = readnodes.get(i);//read
				
				for(int j=0;j<writenodes.size();j++)
				{
					WriteNode wnode = writenodes.get(j);//write
					
					//check read and write are by different threads
					if(rnode.getTid()!=wnode.getTid())
					{
						//create a potential race
						Race race = new Race(trace.getStmtSigIdMap().get(rnode.getID()),
								trace.getStmtSigIdMap().get(wnode.getID()),rnode.getID(),wnode.getID());
						
						//skip redundant races with the same signature, i.e., from same program locations
						if(!violations.contains(race))//&&!potentialviolations.contains(race) may miss real violation with the same signature
						{
							
							//Quick check first: lockset algorithm + weak HB
							
							//lockset algorithm
							if(engine.hasCommonLock(rnode,wnode))
								continue;
							
							//weak HB check
							//a simple reachability analysis to reduce the solver invocations
							if(rnode.getGID()<wnode.getGID())
							{
								if(engine.canReach(rnode, wnode))
									continue;
							}
							else
							{
								if(engine.canReach(wnode,rnode))
									continue;
							}
							
							//If the race passes the quick check, we build constraints
							//for it and determine if it is race by solving the constraints
							
							StringBuilder sb;
							if(config.allconsistent)//all read-write consistency used by the Said approach
							{
								Vector<ReadNode> readNodes_rw = trace.getAllReadNodes();
								sb = engine.constructCausalReadWriteConstraintsOptimized(readNodes_rw, 
										trace.getIndexedWriteNodes(),trace.getInitialWriteValueMap());
							}
							else
							{
								
								//the following builds the constraints for maximal causal model
								
							//get dependent nodes of rnode and wnode
							//if w/o branch information, then all read nodes that happen-before rnode/wnode are considered
							//otherwise, only the read nodes that before the most recent branch nodes before rnode/wnode are considered
							Vector<ReadNode> readNodes_r = trace.getDependentReadNodes(rnode,config.nobranch);
							Vector<ReadNode> readNodes_w = trace.getDependentReadNodes(wnode,config.nobranch);
							
							//construct the optimized read-write constraints ensuring the feasibility of rnode and wnode
							StringBuilder sb1 = engine.constructCausalReadWriteConstraintsOptimized(readNodes_r, 
												trace.getIndexedWriteNodes(),trace.getInitialWriteValueMap());
							StringBuilder sb2 = engine.constructCausalReadWriteConstraintsOptimized(readNodes_w, 
												trace.getIndexedWriteNodes(),trace.getInitialWriteValueMap());
							//conjunct them
							sb = sb1.append(sb2);
							}
							//query the engine to check rnode/wnode forms a race or not
							if(engine.isRace(rnode, wnode,sb))
							{
								//real race found
								
								report("Race: "+race,true);//report it
								violations.add(race);//save it to violations
								
								if(config.noschedule)
									continue;
								
								//generate the corresponding racey schedule

								//for race, there are two schedules:
								//rnode before or after wnode 
								Vector<String> schedule_a = engine.getSchedule(rnode.getGID(),trace.getNodeGIDTIdMap(),trace.getThreadIdNameMap());
								schedule_a.add(trace.getThreadIdNameMap().get(trace.getNodeGIDTIdMap().get(wnode.getGID())));
								
								Vector<String> schedule_b = new Vector<String>(schedule_a);
								
								String str1 = schedule_b.remove(schedule_b.size()-1);
								
								//Due to identical solution to events by other threads
								//rnode may not be immediately before wnode, 								
								//in such a case, we find rnode first and then move it to after wnode
								int pos = schedule_b.size()-1;
								String str2 = schedule_b.remove(pos);
								while(str1==str2)
								{
									pos--;
									schedule_b.add(str2);
									str2 = schedule_b.remove(pos);
								}
								
								schedule_b.add(str1);
								schedule_b.add(str2);

								
								schedule_a.addAll(0, schedule_prefix);
								schedule_b.addAll(0, schedule_prefix);
 
								//add the schedules to the race
								if(rnode.getGID()<wnode.getGID())
								{
									race.addSchedule(schedule_a);
									race.addSchedule(schedule_b);
								}
								else
								{
									race.addSchedule(schedule_b);
									race.addSchedule(schedule_a);
								}
								
								//report the schedules
								report("Schedule_a: "+trim(schedule_a),true);
								report("Schedule_b: "+trim(schedule_b)+"\n",true);
							
							}
							else
							{
								//report potential races
								
								//if we arrive here, it means we find a case where 
								//lockset+happens-before could produce false positive
								if(potentialviolations.add(race))
									report("Potential Race: "+race,false);

							}
						
						}
					}
				}
			}
				//check race write-write
				for(int i=0;i<writenodes.size();i++)//skip the initial write node
				{
					WriteNode wnode1 = writenodes.get(i);
					
					for(int j=0;j<writenodes.size();j++)
					{
						WriteNode wnode2 = writenodes.get(j);
						if(wnode1.getTid()!=wnode2.getTid())
						{
							Race race = new Race(trace.getStmtSigIdMap().get(wnode1.getID()),
									trace.getStmtSigIdMap().get(wnode2.getID()),wnode1.getID(),wnode2.getID());
							
							if(!violations.contains(race))//&&!potentialviolations.contains(race)
							{
								if(engine.hasCommonLock(wnode1,wnode2))
									continue;
								
								if(wnode1.getGID()<wnode2.getGID())
								{
									if(engine.canReach(wnode1, wnode2))
										continue;
								}
								else
								{
									if(engine.canReach(wnode2,wnode1))
										continue;
								}
								
								StringBuilder sb;
								if(config.allconsistent)
								{
									Vector<ReadNode> readNodes_ww = trace.getAllReadNodes();
									sb = engine.constructCausalReadWriteConstraintsOptimized(readNodes_ww, 
											trace.getIndexedWriteNodes(),trace.getInitialWriteValueMap());
								}
								else
								{
								//get dependent nodes of rnode and wnode
								Vector<ReadNode> readNodes_w1 = trace.getDependentReadNodes(wnode1,config.nobranch);
								Vector<ReadNode> readNodes_w2 = trace.getDependentReadNodes(wnode2,config.nobranch);
								
								
								
								StringBuilder sb1 = engine.constructCausalReadWriteConstraintsOptimized(readNodes_w1, 
													trace.getIndexedWriteNodes(),trace.getInitialWriteValueMap());
								StringBuilder sb2 = engine.constructCausalReadWriteConstraintsOptimized(readNodes_w2, 
													trace.getIndexedWriteNodes(),trace.getInitialWriteValueMap());
								sb = sb1.append(sb2);
								}
								//TODO: NEED to ensure that the other non-dependent nodes by other threads are not included
								if(engine.isRace(wnode1, wnode2,sb))
								{
									report("Race: "+race,true);

									violations.add(race); 
									if(config.noschedule)
										continue;
									

									Vector<String> schedule_a = engine.getSchedule(wnode1.getGID(),trace.getNodeGIDTIdMap(),trace.getThreadIdNameMap());
									schedule_a.add(trace.getThreadIdNameMap().get(trace.getNodeGIDTIdMap().get(wnode2.getGID())));


								
									Vector<String> schedule_b = new Vector<String>(schedule_a);
									
									
									String str1 = schedule_b.remove(schedule_b.size()-1);
									int pos = schedule_b.size()-1;
									String str2 = schedule_b.remove(pos);
									while(str1==str2)
									{
										pos--;
										schedule_b.add(str2);
										str2 = schedule_b.remove(pos);
									}
									
									schedule_b.add(str1);
									schedule_b.add(str2);
									
								
									schedule_a.addAll(0, schedule_prefix);
									schedule_b.addAll(0, schedule_prefix);

									if(wnode1.getGID()<wnode2.getGID())
									{
										race.addSchedule(schedule_a);
										race.addSchedule(schedule_b);
									}
									else
									{
										race.addSchedule(schedule_b);
										race.addSchedule(schedule_a);
									}
									
								
									report("Schedule_a: "+trim(schedule_a),true);
									report("Schedule_b: "+trim(schedule_b)+"\n",true);
								
								}
								else
								{
									//if we arrive here, it means we find a case where lockset+happens-before could produce false positive
									if(potentialviolations.add(race))
										report("Potential Race: "+race,false);

								}
							}
						}
					}
				}
		}
	}
	/**
	 * Detect atomicty violations. Not used for race detection.
	 * 
	 * @param engine
	 * @param trace
	 * @param schedule_prefix
	 */
	private static void detectAtomicityViolation(Z3Engine engine, Trace trace,Vector<String> schedule_prefix)
	{
	
		HashMap<String,HashMap<Long,Vector<IMemNode>>> indexedThreadReadWriteNodes 
		= trace.getIndexedThreadReadWriteNodes();
		Iterator<Entry<String, Vector<ReadNode>>> 
		entryIt =trace.getIndexedReadNodes().entrySet().iterator();
		while(entryIt.hasNext())
		{
			Entry<String, Vector<ReadNode>> entry = entryIt.next();
			String addr = entry.getKey();
			
			//get all write nodes on the address
			Vector<WriteNode> writenodes = trace.getIndexedWriteNodes().get(addr);
			if(writenodes==null||writenodes.size()<1)
			continue;
						
			//check atomicity-violation all nodes
			HashMap<Long,Vector<IMemNode>> threadReadWriteNodes = indexedThreadReadWriteNodes.get(addr);
			
			Object[] threads = threadReadWriteNodes.keySet().toArray();
	
			for(int i=0;i<threads.length-1;i++)
				for(int j=i+1;j<threads.length;j++)
				{
					Vector<IMemNode> rwnodes1 = threadReadWriteNodes.get(threads[i]);
					
					Vector<IMemNode> rwnodes2 = threadReadWriteNodes.get(threads[j]);
	
					if(rwnodes1!=null&rwnodes2!=null&&rwnodes1.size()>1)
					{
						for(int k=0;k<rwnodes1.size()-1;k++)
						{
							//require atomic region specification
							IMemNode node1 = rwnodes1.get(k);
							IMemNode node2 = rwnodes1.get(k+1);
							
							for(int m =0;m<rwnodes2.size();m++)
							{
								IMemNode node3 = rwnodes2.get(m);
								
								if(node1.getType()==TYPE.WRITE
										||node2.getType()==TYPE.WRITE
										||node3.getType()==TYPE.WRITE)
								{
									AtomicityViolation av = new AtomicityViolation(trace.getStmtSigIdMap().get(node1.getID()),
																				trace.getStmtSigIdMap().get(node3.getID()),
																				trace.getStmtSigIdMap().get(node2.getID()),node1.getID(),node2.getID(),node3.getID());
									
									
									if(!violations.contains(av))//&&!potentialviolations.contains(av)
									{
										
										//it's possible that av is true in the original execution
										//no need to model its constraints
										//TODO: optimize it!
										
										//TODO: Need to check lock-history
										if(engine.isAtomic(node1,node2,node3))
											continue;
										
										if(node3.getGID()<node1.getGID())
										{
											if(engine.canReach((AbstractNode)node3, (AbstractNode)node1))
												continue;
										}
										else if(node3.getGID()>node2.getGID())
										{
											if(engine.canReach((AbstractNode)node2, (AbstractNode)node3))
												continue;
										}
										
										//get dependent read nodes
										Vector<ReadNode> readNodes_1 = trace.getDependentReadNodes(node1,config.nobranch);
										Vector<ReadNode> readNodes_2 = trace.getDependentReadNodes(node2,config.nobranch);
										Vector<ReadNode> readNodes_3 = trace.getDependentReadNodes(node3,config.nobranch);

										StringBuilder sb1 = engine.constructCausalReadWriteConstraintsOptimized(readNodes_1, 
												trace.getIndexedWriteNodes(),trace.getInitialWriteValueMap());
										StringBuilder sb2 = engine.constructCausalReadWriteConstraintsOptimized(readNodes_2, 
												trace.getIndexedWriteNodes(),trace.getInitialWriteValueMap());
										StringBuilder sb3 = engine.constructCausalReadWriteConstraintsOptimized(readNodes_3, 
												trace.getIndexedWriteNodes(),trace.getInitialWriteValueMap());

										
										if(engine.isAtomicityViolation(node1, node2, node3,sb1,sb2,sb3))
										{
											
											report("Atomicity Violation: "+av,true);

											violations.add(av);
	
											if(config.noschedule)
												continue;
											
											Vector<String> schedule = engine.getSchedule(node3.getGID(),trace.getNodeGIDTIdMap(),trace.getThreadIdNameMap());
											
											schedule.addAll(0, schedule_prefix);

											av.addSchedule(schedule);
											report("Schedule: "+trim(schedule)+"\n",true);
										}
										else
										{
											//if we arrive here, it means we find a case where lockset+happens-before could produce false positive
											report("Potential Atomicity Violation: "+av+"\n",false);
											potentialviolations.add(av);

										}
									}
								}
							}
						}
					}
				}
		}
	}
	
	/**
	 * The input is the application name and the optional options
	 * 
	 * @param args
	 */
	public static void main(String[] args) {

		config = new Configuration(args);
		
		try{
			
			//Now let's start predict analysis
			long start_time = System.currentTimeMillis();
			
			//initialize printer
			initPrinter(config.appname);
			
			//db engine is used for interacting with database
			DBEngine db = new DBEngine(config.appname);

			//load all the metadata in the application
			HashMap<Integer, String> sharedVarIdSigMap = db.getSharedVarSigIdMap();
			HashMap<Integer, String> stmtIdSigMap = db.getStmtSigIdMap();
			HashMap<Long,String> threadIdNameMap = db.getThreadIdNameMap();
			

			//the total number of events in the trace
			long TOTAL_TRACE_LENGTH = db.getTraceSize();

			//this is used to maintain the schedule in the previous windows
			Vector<String> schedule_prefix = new Vector<String>();

			//z3 engine is used for interacting with constraints
			Z3Engine engine;
			if(config.smtlib1)//if yices smt format
				engine = new YicesEngineSMTLIB1(config);
			else//use z3 smt2 format by default
				engine = new Z3Engine(config);
			
			//map from memory address to the initial value  
			HashMap<String,String> initialWriteValueMap = new HashMap<String,String>();
			
			//process the trace window by window
			for(int round =0;round*config.window_size<TOTAL_TRACE_LENGTH;round++)
			{
				long index_start = round*config.window_size+1;
				long index_end = (round+1)*config.window_size;
				//if(TOTAL_TRACE_LENGTH>MAX_LENGTH)System.out.println("***************** Round "+(round+1)+": "+index_start+"-"+index_end+"/"+TOTAL_TRACE_LENGTH+" ******************\n");
				
				
				//load trace
				Trace trace = db.getTrace(index_start,index_end);
				//metadata
				trace.setSharedVarIdSigMap(sharedVarIdSigMap);
				trace.setStmtIdSigMap(stmtIdSigMap);
				trace.setThreadIdNameMap(threadIdNameMap);	
				
				//starting from the second window, the initial value map becomes
				//the last write map in the last window
				if(round>0)trace.setInitialWriteValueMap(initialWriteValueMap);
				
				
				//Now, construct the constraints
				
				//1. declare all variables 
				engine.declareVariables(trace.getFullTrace());
				//2. intra-thread order for all nodes, excluding branches and basic block transitions
				if(config.rmm_pso)//TODO: add intra order between sync
					engine.addPSOIntraThreadConstraints(trace.getIndexedThreadReadWriteNodes());
				else
					engine.addIntraThreadConstraints(trace.getThreadNodesMap());

				//3. order for locks, signals, fork/joins
				engine.addSynchronizationConstraints(trace,trace.getSyncNodesMap(),trace.getThreadFirstNodeMap(),trace.getThreadLastNodeMap());
				
				//4. match read-write
				//This is only used for constructing all read-write consistency constraints
				
				//engine.addReadWriteConstraints(trace.getIndexedReadNodes(),trace.getIndexedWriteNodes());
				//engine.addReadWriteConstraints(trace.getIndexedReadNodes(),trace.getIndexedWriteNodes());

					

				if(detectRace)//detecting races
				{
					detectRace(engine,trace,schedule_prefix);
				}
				if(detectAtomicityViolation)//detecting atomicity violations
				{
					detectAtomicityViolation(engine,trace,schedule_prefix);
				}
				if(detectDeadlock)//when detecting deadlocks, make sure the lock constraints of the rest unlock nodes are disabled
				{
					 detectDeadlock(engine,trace,schedule_prefix);
				}
				
				//get last write value from the current trace 
				//as the initial value for the next round
				initialWriteValueMap = trace.getInitialWriteValueMap();
				trace.saveLastWriteValues(initialWriteValueMap); 
				
				//append the schedule in the current trace to schedule_prefix
				//which maybe used in the next window for generating racey schedules
				if((round+1)*config.window_size<TOTAL_TRACE_LENGTH)
					schedule_prefix.addAll(getTraceSchedule(trace));
			}
			
			
			//Report statistics about the trace and race detection

			int TOTAL_THREAD_NUMBER = db.getTraceThreadNumber();
			int TOTAL_SHAREDVARIABLE_NUMBER = db.getTraceSharedVariableNumber();
			int TOTAL_BRANCH_NUMBER = db.getTraceBranchNumber();
			int TOTAL_READWRITE_NUMBER = db.getTraceReadWriteNumber();
			int TOTAL_SYNC_NUMBER = db.getTraceSyncNumber();
			
			report("Trace Size: "+TOTAL_TRACE_LENGTH,false);
			report("Total #Threads: "+TOTAL_THREAD_NUMBER,false);
			report("Total #SharedVariables: "+TOTAL_SHAREDVARIABLE_NUMBER,false);
			report("Total #Read-Writes: "+TOTAL_READWRITE_NUMBER,false);
			report("Total #Synchronizations: "+TOTAL_SYNC_NUMBER,false);
			report("Total #Branches: "+TOTAL_BRANCH_NUMBER,false);
			report("Total #Potential Violations: "+(potentialviolations.size()+violations.size()),false);
			report("Total #Real Violations: "+violations.size(),false);
			report("Total Time: "+(System.currentTimeMillis()-start_time)+"ms",false); 
			//System.out.println("Total #Schedules: "+size_schedule);
			
			
			if(!config.noschedule)
			{
				//save schedules to db
				int size = violations.size();
				if(size>0)
				{
					db.createScheduleTable();
					db.saveSchedulesToDB(violations);			
				}
			}
		}
		catch(Exception e)
		  {
			  e.printStackTrace();
		  }
		finally
		{
			closePrinter();
		}
	}
	
	/**
	 * Return the schedule, i.e., the thread execution order, of the trace
	 * 
	 * @param trace
	 * @return
	 */
	private static Vector<String> getTraceSchedule(Trace trace) {
		
		Vector<String> fullschedule = new Vector<String>();
		
		for(int k =0;k<trace.getFullTrace().size();k++)
			fullschedule.add(trace.getThreadIdNameMap().get(trace.getFullTrace().get(k).getTid()));
		
		return fullschedule;
	}
	

	

}
