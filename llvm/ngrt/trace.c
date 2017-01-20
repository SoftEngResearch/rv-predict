#include <assert.h>
#include <sys/types.h>	/* for open(2) */
#include <sys/stat.h>	/* for open(2) */
#include <fcntl.h>	/* for open(2) */
#include <sys/uio.h>	/* for writev(2) */

#include "access.h"
#include "nbcompat.h"
#include "notimpl.h"
#include "ring.h"
#include "rvpint.h"
#include "thread.h"
#include "trace.h"
#include "tracefmt.h"

static __section(".text") deltops_t deltops = { .matrix = { { 0 } } };

typedef struct _threadswitch {
	uintptr_t deltop;
	uint32_t id;
} __packed __aligned(sizeof(uint32_t)) threadswitch_t;

static const rvp_trace_header_t header = {
	  .th_magic = "RVP_"
	, . th_version = 0
	, .th_byteorder = '0' | ('1' << 8) | ('2' << 16) | ('3' << 24)
	, .th_pointer_width = sizeof(uintptr_t)
	, .th_data_width = sizeof(uint32_t)
};

static ssize_t
writeall(int fd, const void *buf, size_t nbytes)
{
	ssize_t nwritten, nleft;
	const char *next;

	for (next = buf, nleft = nbytes;  
	     nleft != 0;
	     next += nwritten, nleft -= nwritten) {
		if ((nwritten = write(fd, next, nleft)) == -1) {
			return -1;
		}
	}
	return nwritten;
}

int
rvp_trace_open(void)
{
	int fd = open("./rvpredict.trace", O_WRONLY|O_CREAT|O_TRUNC, 0600);

	if (fd == -1)
		return -1;

	if (writeall(fd, &header, sizeof(header)) == -1) {
		close(fd);
		return -1;
	}

	return fd;
}

bool
rvp_thread_flush_to_fd(rvp_thread_t *t, int fd, bool trace_switch)
{
	int iovcnt = 0;
	ssize_t nwritten;
	rvp_ring_t *r = &t->t_ring;
	uint32_t *producer = r->r_producer, *consumer = r->r_consumer;
	threadswitch_t threadswitch = {
		  .deltop =
		      (uintptr_t)rvp_vec_and_op_to_deltop(0, RVP_OP_SWITCH)
		, .id = t->t_id
	};
	struct iovec iov[3];

	if (consumer == producer)
		return false;

	if (trace_switch) {
		iov[iovcnt++] = (struct iovec){
			  .iov_base = &threadswitch
			, .iov_len = sizeof(threadswitch)
		};
	}

	if (consumer < producer) {
		iov[iovcnt++] = (struct iovec){
			  .iov_base = consumer
			, .iov_len = (producer - consumer) *
				     sizeof(consumer[0])
		};
	} else {	/* consumer > producer */
		iov[iovcnt++] = (struct iovec){
			  .iov_base = consumer
			, .iov_len = (r->r_last + 1 - consumer) *
				     sizeof(consumer[0])
		};
		iov[iovcnt++] = (struct iovec){
			  .iov_base = r->r_items
			, .iov_len = (producer - r->r_items) *
				     sizeof(r->r_items[0])
		};
	}
	nwritten = writev(fd, iov, iovcnt);

	while (--iovcnt >= 0)
		nwritten -= iov[iovcnt].iov_len;

	assert(nwritten == 0);
	r->r_consumer = producer;
	return true;
}

void
rvp_ring_put_addr(rvp_ring_t *r, const void *addr)
{
	int i;
	union {
		uintptr_t uaddr;
		uint32_t u32[sizeof(uintptr_t) / sizeof(uint32_t)];
	} addru = {.uaddr = (uintptr_t)addr};

	for (i = 0; i < __arraycount(addru.u32); i++) {
		rvp_ring_put(r, addru.u32[i]);
	}
}

deltop_t *
rvp_vec_and_op_to_deltop(int jmpvec, rvp_op_t op)
{
	deltop_t *deltop =
	    &deltops.matrix[__arraycount(deltops.matrix) / 2 + jmpvec][op];

	if (deltop < &deltops.matrix[0][0] ||
		     &deltops.matrix[RVP_NJMPS - 1][RVP_NOPS - 1] < deltop)
		return NULL;
	
	return deltop;
}

void
rvp_ring_put_pc_and_op(rvp_ring_t *r, const char *pc, rvp_op_t op)
{
	int jmpvec = pc - r->r_lastpc;
	deltop_t *deltop;

	deltop = rvp_vec_and_op_to_deltop(jmpvec, op);

	r->r_lastpc = pc;

	if (deltop == NULL) {
		rvp_ring_put_addr(r, pc);
		deltop = rvp_vec_and_op_to_deltop(0, op);
		assert(deltop != NULL);
	}
	rvp_ring_put_addr(r, deltop);
}

void
rvp_ring_put_begin(rvp_ring_t *r, uint32_t id)
{
	r->r_lastpc = __builtin_return_address(1);
	rvp_ring_put_addr(r, rvp_vec_and_op_to_deltop(0, RVP_OP_BEGIN));
	rvp_ring_put(r, id);
	rvp_ring_put_addr(r, r->r_lastpc);
}

void
rvp_ring_put_u64(rvp_ring_t *r, uint64_t val)
{
	union {
		uint64_t u64;
		uint32_t u32[2];
	} valu = {.u64 = val};

	rvp_ring_put(r, valu.u32[0]);
	rvp_ring_put(r, valu.u32[1]);
}