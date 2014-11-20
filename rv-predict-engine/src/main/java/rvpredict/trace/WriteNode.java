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

public class WriteNode extends AbstractNode implements MemoryAccessEvent {
    private long prevBranchId;

    private long value;
    private long objectHashCode;
    private long index;

    public WriteNode(long GID, long tid, int ID, long objectHashCode, long index, long value) {
        super(GID, tid, ID, EventType.WRITE);
        this.objectHashCode = objectHashCode;
        this.index = index;
        this.value = value;
    }

    @Override
    public long getValue() {
        return value;
    }

    @Override
    public String getAddr() {
        return (objectHashCode == 0 ? "_" : objectHashCode + "_") +
                (index < 0 ? "." + -index : index);
    }

    @Override
    public String toString() {
        return globalId + ": thread " + threadId + " " + synId + " " + objectHashCode + " " + index + " " + value + " " + type;
    }

    @Override
    public long getPrevBranchId() {
        return prevBranchId;
    }

    @Override
    public void setPrevBranchId(long id) {
        prevBranchId = id;
    }

}
