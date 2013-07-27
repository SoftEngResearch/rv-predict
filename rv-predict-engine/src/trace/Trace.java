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
package trace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Stack;
import java.util.Vector;

/**
 * Representation of the execution trace. Each event is created as a node
 * with a corresponding type. Events are indexed by their thread Id, Type,
 * and memory address.
 * 
 * @author jeffhuang
 *
 */
public class Trace {

	//fulltrace represents all the events in the global order
	Vector<AbstractNode> fulltrace = new Vector<AbstractNode>();
	
	//keep a node GID to tid Map, used for generating schedules
	HashMap<Long, Long> nodeGIDTidMap = new HashMap<Long, Long>();
	
	//per thread node map
	HashMap<Long,Vector<AbstractNode>> threadNodesMap = new HashMap<Long,Vector<AbstractNode>>();
	
	//the first node and last node map of each thread
	HashMap<Long, AbstractNode> threadFirstNodeMap = new HashMap<Long, AbstractNode>();
	HashMap<Long, AbstractNode> threadLastNodeMap = new HashMap<Long, AbstractNode>();
	
	//per thread per lock lock/unlock pair
	HashMap<Long,HashMap<String,Vector<LockPair>>> threadIndexedLockPairs = new HashMap<Long,HashMap<String,Vector<LockPair>>>();
	HashMap<Long,Stack<ISyncNode>> threadSyncStack = new HashMap<Long,Stack<ISyncNode>>();

	//per thread branch nodes and basicblock nodes
	HashMap<Long,Vector<BranchNode>> threadBranchNodes = new HashMap<Long,Vector<BranchNode>>();
	HashMap<Long,Vector<BBNode>> threadBBNodes = new HashMap<Long,Vector<BBNode>>();
	
	//per thead synchronization nodes
	HashMap<String,Vector<ISyncNode>> syncNodesMap = new HashMap<String,Vector<ISyncNode>>();	

	//per address read and write nodes 
	HashMap<String,Vector<ReadNode>> indexedReadNodes = new HashMap<String,Vector<ReadNode>>();
	HashMap<String,Vector<WriteNode>> indexedWriteNodes = new HashMap<String,Vector<WriteNode>>();

	//per address map from thread id to read/write nodes
	HashMap<String,HashMap<Long,Vector<IMemNode>>> indexedThreadReadWriteNodes = new HashMap<String,HashMap<Long,Vector<IMemNode>>>();

	//per address initial write value
	HashMap<String,String> initialWriteValueMap = new HashMap<String,String>();
	
	Vector<ReadNode> allReadNodes;

	//metadata
	HashMap<Long, String> threadIdNamemap;
	HashMap<Integer, String> sharedVarIdSigMap;
	HashMap<Integer, String> stmtIdSigMap;
	
	public Vector<AbstractNode> getFullTrace()
	{
		return fulltrace;
	}
	public HashMap<String,String> getInitialWriteValueMap()
	{
		return initialWriteValueMap;
	}
	public void setInitialWriteValueMap(HashMap<String,String> map)
	{
		initialWriteValueMap = map;
	}
	public HashMap<Long, Long> getNodeGIDTIdMap() {
		return nodeGIDTidMap;
	}
	public void setSharedVarIdSigMap(HashMap<Integer, String> map) {
		
		sharedVarIdSigMap = map;
	}
	public void setStmtIdSigMap(HashMap<Integer, String> map) {
		
		stmtIdSigMap = map;
	}
	public void setThreadIdNameMap(HashMap<Long, String> map)
	{
		threadIdNamemap = map;
	}
	public HashMap<Integer, String> getSharedVarIdMap() {
		
		return sharedVarIdSigMap;
	}
	public HashMap<Integer, String> getStmtSigIdMap() {
		
		return stmtIdSigMap;
	}
	public HashMap<Long, String> getThreadIdNameMap()
	{
		return threadIdNamemap;
	}

	
	public HashMap<Long, AbstractNode> getThreadFirstNodeMap()
	{
		return threadFirstNodeMap;
	}
	public HashMap<Long, AbstractNode> getThreadLastNodeMap()
	{
		return threadLastNodeMap;
	}
	public HashMap<Long,Vector<AbstractNode>> getThreadNodesMap()
	{
		return threadNodesMap;
	}
	public HashMap<String,Vector<ISyncNode>> getSyncNodesMap()
	{
		return syncNodesMap;
	}
	public HashMap<Long,HashMap<String,Vector<LockPair>>> getThreadIndexedLockPairs()
	{
		return threadIndexedLockPairs;
	}
	public HashMap<String,Vector<ReadNode>> getIndexedReadNodes()
	{
		return indexedReadNodes;
	}
	public HashMap<String,Vector<WriteNode>> getIndexedWriteNodes()
	{
		return indexedWriteNodes;
	}
	public HashMap<String,HashMap<Long,Vector<IMemNode>>> getIndexedThreadReadWriteNodes()
	{
		return indexedThreadReadWriteNodes;
	}
	public void saveLastWriteValues(
			HashMap<String, String> valueMap) {
		
		Iterator<String> addrIt = indexedWriteNodes.keySet().iterator();
		while(addrIt.hasNext())
		{
			String addr = addrIt.next();
			valueMap.put(addr, indexedWriteNodes.get(addr).lastElement().getValue());
		}
	}
	public Vector<ReadNode> getAllReadNodes()
	{
		if(allReadNodes == null)
		{
			allReadNodes = new Vector<ReadNode>();
			Iterator<Vector<ReadNode>> it = indexedReadNodes.values().iterator();
			while(it.hasNext())
			{
				allReadNodes.addAll(it.next());
			}
		}
		
		return allReadNodes;
	}
	
	//TODO: NEED to include the dependent nodes from other threads
	public Vector<ReadNode> getDependentReadNodes(IMemNode rnode, boolean nobranch) {
		
		Vector<ReadNode> readnodes = new Vector<ReadNode>();
		long tid = rnode.getTid();
		long POS = rnode.getGID()-1;
		if(!nobranch)
		{
			long pos = -1;
			Vector<BranchNode> branchNodes = threadBranchNodes.get(tid);
			if(branchNodes!=null)
			//TODO: improve to log(n) complexity
			for(int i =0;i<branchNodes.size();i++)
			{
				long id = branchNodes.get(i).getGID();
				if(id>POS)
					break;
				else
					pos =id;
			}
			POS = pos;
		}
		
		
		if(POS>=0)
		{
			Vector<AbstractNode> nodes = threadNodesMap.get(tid);//TODO: optimize here to check only READ node 
			for(int i =0;i<nodes.size();i++)
			{
				AbstractNode node = nodes.get(i);
				if(node.getGID()>POS)
					break;
				else
				{
					if(node instanceof ReadNode)
						readnodes.add((ReadNode) node);
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
	public void addNode(AbstractNode node)
	{
		Long tid = node.getTid();
		
		if(node instanceof BBNode)
		{
			//basicblock node
			
			Vector<BBNode> bbnodes = threadBBNodes.get(tid);
			if(bbnodes == null)
			{
				bbnodes = new Vector<BBNode>();
				threadBBNodes.put(tid, bbnodes);
			}
			bbnodes.add((BBNode)node);
		}
		else if(node instanceof BranchNode)
		{
			//branch node
			
			Vector<BranchNode> branchnodes = threadBranchNodes.get(tid);
			if(branchnodes == null)
			{
				branchnodes = new Vector<BranchNode>();
				threadBranchNodes.put(tid, branchnodes);
			}
			branchnodes.add((BranchNode)node);
		}
		else if (node instanceof InitNode)
		{
			//initial write node
			
			initialWriteValueMap.put(((InitNode) node).getAddr(),((InitNode) node).getValue());
		}
		else
		{
			//all critical nodes -- read/write/synchronization events 
			
			fulltrace.add(node);

			nodeGIDTidMap.put(node.getGID(), node.getTid());
			
			Vector<AbstractNode> threadNodes = threadNodesMap.get(tid);
			if(threadNodes ==null)
			{
				threadNodes = new Vector<AbstractNode>();
				threadNodesMap.put(tid, threadNodes);
				threadFirstNodeMap.put(tid, node);
				
			}
			
			threadNodes.add(node);
			
			//TODO: Optimize it -- no need to update it every time
			threadLastNodeMap.put(tid, node); 
			if(node instanceof IMemNode)
			{
				String addr = ((IMemNode)node).getAddr();

				HashMap<Long, Vector<IMemNode>> threadReadWriteNodes = indexedThreadReadWriteNodes.get(addr);
				if(threadReadWriteNodes==null)
				{
					threadReadWriteNodes = new HashMap<Long, Vector<IMemNode>>();
					indexedThreadReadWriteNodes.put(addr, threadReadWriteNodes);
				}
				Vector<IMemNode> rwnodes = threadReadWriteNodes.get(tid);
				if(rwnodes==null)
				{
					rwnodes =  new Vector<IMemNode>();
					threadReadWriteNodes.put(tid, rwnodes);
				}
				rwnodes.add((IMemNode)node);
				
				if(node instanceof ReadNode)
				{
					
					Vector<ReadNode> readNodes = indexedReadNodes.get(addr);
					if(readNodes == null)
					{
						readNodes =  new Vector<ReadNode>();
						indexedReadNodes.put(addr, readNodes);
					}
					readNodes.add((ReadNode)node);
					
				}
				else //write node
				{
					Vector<WriteNode> writeNodes = indexedWriteNodes.get(addr);
					if(writeNodes ==null)
					{
						writeNodes = new Vector<WriteNode>();
						indexedWriteNodes.put(addr, writeNodes);
					}
					writeNodes.add((WriteNode)node);
				}
			}
			else
			{
				//synchronization nodes
				
				String addr = ((ISyncNode)node).getAddr();
				Vector<ISyncNode> syncNodes = syncNodesMap.get(addr);
				if(syncNodes==null)
				{
					syncNodes = new Vector<ISyncNode>();
					syncNodesMap.put(addr, syncNodes);
				}
				
				syncNodes.add((ISyncNode)node);
				
				
				if (node instanceof LockNode)
				{
					Stack<ISyncNode> stack = threadSyncStack.get(tid);
					if(stack==null)
					{
						stack = new Stack<ISyncNode>();
						threadSyncStack.put(tid, stack);
					}
					
					stack.push((LockNode)node);					
				}
				else if (node instanceof UnlockNode)
				{
					HashMap<String,Vector<LockPair>> indexedLockpairs = threadIndexedLockPairs.get(tid);
					if(indexedLockpairs==null)
					{
						indexedLockpairs = new HashMap<String,Vector<LockPair>>();
						threadIndexedLockPairs.put(tid, indexedLockpairs);
					}					
					Vector<LockPair> lockpairs = indexedLockpairs.get(addr);
					if(lockpairs==null)
					{
						lockpairs = new Vector<LockPair>();
						indexedLockpairs.put(addr, lockpairs);
					}
					
					Stack<ISyncNode> stack = threadSyncStack.get(tid);
					if(stack==null)
					{
						stack = new Stack<ISyncNode>();
						threadSyncStack.put(tid, stack);
					}
					//assert(stack.size()>0); //this is possible when segmented
					if(stack.size()==0)
						lockpairs.add(new LockPair(null,(UnlockNode)node));
					else if(stack.size()==1)
						lockpairs.add(new LockPair(stack.pop(),(UnlockNode)node));
					else 
						stack.pop();//handle reentrant lock
				}
			}
		}
	}
	/**
	 * when trace is completely loaded, compute the lock/unlock pairs
	 * because we analyze the trace window by window, lock/unlock may not be
	 * in the same window, so we may have null lock or unlock node in the pair.
	 */
	public void finishedLoading()
	{
		//check threadSyncStack - only to handle when segmented
		Iterator<Entry<Long,Stack<ISyncNode>>> entryIt = threadSyncStack.entrySet().iterator();
		while(entryIt.hasNext())
		{
			Entry<Long,Stack<ISyncNode>> entry = entryIt.next();
			Long tid = entry.getKey();
			Stack<ISyncNode> stack = entry.getValue();
			
			if(!stack.isEmpty())
			{
				HashMap<String,Vector<LockPair>> indexedLockpairs = threadIndexedLockPairs.get(tid);
				if(indexedLockpairs==null)
				{
					indexedLockpairs = new HashMap<String,Vector<LockPair>>();
					threadIndexedLockPairs.put(tid, indexedLockpairs);
				}
				
				while(!stack.isEmpty())
				{
					ISyncNode syncnode = stack.pop();//lock or wait
										
					Vector<LockPair> lockpairs = indexedLockpairs.get(syncnode.getAddr());
					if(lockpairs==null)
					{
						lockpairs = new Vector<LockPair>();
						indexedLockpairs.put(syncnode.getAddr(), lockpairs);
					}
					
					lockpairs.add(new LockPair(syncnode,null));
				}
			}
		}
	}

}