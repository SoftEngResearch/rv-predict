/* Copyright (c) 2017 Runtime Verification, Inc.  All rights reserved. */

#ifndef _RVP_RING_H_
#define _RVP_RING_H_

#include <sched.h>
#include <stdatomic.h>	/* for atomic_store_explicit */
#include <stdbool.h>
#include <stdint.h>	/* for uint32_t */
#include <string.h>	/* for memcpy(3) */
#include <unistd.h>	/* for size_t */
#include <sys/uio.h>	/* for struct iovec */

#include "buf.h"
#include "relay.h"

typedef enum _rvp_ring_state {
	  RVP_RING_S_INUSE	= 0
	, RVP_RING_S_CLEAN
	, RVP_RING_S_DIRTY
} rvp_ring_state_t;

struct _rvp_ring;
typedef struct _rvp_ring rvp_ring_t;

typedef struct _rvp_lastctx {
	uint32_t lc_tid;
	uint32_t lc_idepth;
} rvp_lastctx_t;

typedef struct _rvp_interruption {
	rvp_ring_t *	it_interruptor;
	int		it_interrupted_idx;
	int		it_interruptor_sidx;
	int		it_interruptor_eidx;
} rvp_interruption_t;

/* interruptions ring */
typedef struct _rvp_iring {
	rvp_interruption_t * _Atomic volatile ir_producer,
	                   * _Atomic volatile ir_consumer;
	rvp_interruption_t ir_items[8];
} rvp_iring_t;

struct _rvp_ring {
	uint32_t * _Atomic volatile r_producer, * _Atomic volatile r_consumer;
	uint32_t *r_last;
	uint32_t *r_items;
	const char *r_lastpc;
	volatile uint64_t r_lgen;	// thread-local generation number
	rvp_ring_t *r_next;
	rvp_ring_state_t _Atomic r_state;
	uint32_t r_tid;
	uint32_t r_idepth;
	rvp_iring_t r_iring;
	rvp_sigdepth_t r_sigdepth;
};

extern volatile _Atomic uint64_t rvp_ggen;
extern unsigned int rvp_log2_nthreads;

static inline void
rvp_increase_ggen(void)
{
	(void)atomic_fetch_add_explicit(&rvp_ggen, 1, memory_order_release);
}

static inline uint64_t
rvp_ggen_before_store(void)
{
	// acquire semantics ensure that the global generation load
	// precedes the following instrumented store
	return atomic_load_explicit(&rvp_ggen, memory_order_acquire);
}

static inline uint64_t
rvp_ggen_after_load(void)
{
	// ensure that the instrumented load precedes the global
	// generation load
	atomic_thread_fence(memory_order_acquire);
	return atomic_load_explicit(&rvp_ggen, memory_order_acquire);
}

static inline void
rvp_buf_trace_cog(rvp_buf_t *b, volatile uint64_t *lgenp, uint64_t gen)
{
	if (*lgenp < gen) {
		*lgenp = gen;
		rvp_buf_put_cog(b, gen);
	}
}

static inline void
rvp_buf_trace_load_cog(rvp_buf_t *b, volatile uint64_t *lgenp)
{
	rvp_buf_trace_cog(b, lgenp, rvp_ggen_after_load());
}

void rvp_ring_init(rvp_ring_t *, uint32_t *, size_t);
void rvp_ring_wait_for_slot(rvp_ring_t *, uint32_t *);
void rvp_ring_wait_for_nempty(rvp_ring_t *, int);
void rvp_wake_transmitter(void);

static inline int
rvp_iring_nfull(const rvp_iring_t *ir)
{
	rvp_interruption_t *producer = ir->ir_producer,
	                   *consumer = ir->ir_consumer;

	if (producer >= consumer)
		return producer - consumer;

	return __arraycount(ir->ir_items) - (consumer - producer);
}

static inline int
rvp_iring_capacity(rvp_iring_t *ir)
{
	return __arraycount(ir->ir_items) - 1;
}

static inline int
rvp_iring_nempty(rvp_iring_t *ir)
{
	return rvp_iring_capacity(ir) - rvp_iring_nfull(ir);
}

static inline int
rvp_ring_nfull(const rvp_ring_t *r)
{
	uint32_t *producer = r->r_producer,
	         *consumer = r->r_consumer;

	if (producer >= consumer)
		return producer - consumer;

	return (r->r_last - r->r_items) + 1 - (consumer - producer);
}

static inline int
rvp_ring_capacity(rvp_ring_t *r)
{
	return r->r_last - r->r_items;
}

static inline int
rvp_ring_nempty(rvp_ring_t *r)
{
	return rvp_ring_capacity(r) - rvp_ring_nfull(r);
}

static inline void
rvp_ring_request_service(rvp_ring_t *r)
{
	if (r->r_idepth == 0)
		rvp_wake_transmitter();
	else
		rvp_wake_relay();
}

static inline void
rvp_ring_await_nempty(rvp_ring_t *r, int nempty)
{
	rvp_ring_request_service(r);
	rvp_ring_wait_for_nempty(r, nempty);
}

static inline void
rvp_iring_wait_for_one_empty(rvp_ring_t *r)
{
	rvp_iring_t *ir = &r->r_iring;
	int i;
	volatile int j;

	for (i = 32; rvp_iring_nempty(ir) < 1; i = MIN(16384, i + 1)) {
		for (j = 0; j < i; j++)
			;
		/* we call this when we're queueing an interruption, and
		 * we only do that from a signal context, so we mustn't
		 * call sched_yield() here.
		 */
	}
}

static inline void
rvp_iring_await_one_empty(rvp_ring_t *r)
{
	rvp_ring_request_service(r);
	rvp_iring_wait_for_one_empty(r);
}

static inline void
rvp_ring_open_slot(rvp_ring_t *r, uint32_t *slot)
{
	rvp_ring_request_service(r);
	rvp_ring_wait_for_slot(r, slot);
}

static inline const rvp_interruption_t *
rvp_iring_last(const rvp_iring_t *ir)
{
	return &ir->ir_items[__arraycount(ir->ir_items) - 1];
}

static inline const rvp_interruption_t *
rvp_ring_next_interruption(rvp_ring_t *r, const rvp_interruption_t *prev)
{
	rvp_iring_t *ir = &r->r_iring;
	rvp_interruption_t *producer = ir->ir_producer;
	const rvp_interruption_t *next =
	    (prev == rvp_iring_last(ir)) ? &ir->ir_items[0] : (prev + 1);

	if (next == producer)
		return NULL;

	return next;
}

static inline const rvp_interruption_t *
rvp_ring_first_interruption(rvp_ring_t *r)
{
	rvp_iring_t *ir = &r->r_iring;
	rvp_interruption_t *consumer = ir->ir_consumer,
	                   *producer = ir->ir_producer;

	if (consumer == producer)
		return NULL;

	return consumer;
}

static inline void
rvp_ring_drop_interruption(rvp_ring_t *r)
{
	rvp_iring_t *ir = &r->r_iring;
	rvp_interruption_t *prev = ir->ir_consumer;
	rvp_interruption_t *next =
	    (prev == rvp_iring_last(ir)) ? &ir->ir_items[0] : (prev + 1);

	assert(prev != ir->ir_producer);

	atomic_store_explicit(&ir->ir_consumer, next, memory_order_release);
}

static inline int
rvp_ring_consumer_index_advanced_by(const rvp_ring_t *r, int nitems)
{
	uint32_t *prev = r->r_consumer;
	uint32_t *next;

	assert(nitems < rvp_ring_nfull(r));

	if (prev + nitems <= r->r_last) {
		next = prev + nitems;
	} else {
		const int ringsz = r->r_last + 1 - r->r_items;
		next = prev + (nitems - ringsz);
	}
	return next - r->r_items;
}

static inline void
rvp_ring_put_multiple(rvp_ring_t *r, const uint32_t *item, int nitems)
{
	uint32_t *prev = r->r_producer;
	uint32_t *next;

	if (prev + nitems <= r->r_last) {
		next = prev + nitems;
	} else {
		const int ringsz = r->r_last + 1 - r->r_items;
		next = prev + (nitems - ringsz);
	}

	/* TBD do we need to order the r_consumer, r_producer reads? */

	while (rvp_ring_nempty(r) < nitems)
		rvp_ring_await_nempty(r, nitems);

	if (prev < next) {
		memcpy(prev, item, nitems * sizeof(prev[0]));
	} else {
		int nfirst = r->r_last - prev + 1,
		    nlast = next - r->r_items;
		memcpy(prev, item, nfirst * sizeof(item[0]));
		memcpy(r->r_items, &item[nfirst], nlast * sizeof(item[0]));
	}

	atomic_store_explicit(&r->r_producer, next, memory_order_release);

	int nslots = rvp_ring_capacity(r) + 1;
	int ggen_threshold = nslots >> (1 + rvp_log2_nthreads);
	int service_threshold = nslots / 2;
	int nfull = rvp_ring_nfull(r);

	/* Increase the global generation number every time the producer
	 * pointer in a per-thread event ring passes milestones that are
	 * ggen_threshold apart.  Milestones get closer to each other
	 * (ggen_threshold gets smaller) with more running threads, so
	 * that opportunities to start new windows appear fairly regularly
	 * no matter what level of concurrency.
	 */
	if (nitems >= ggen_threshold ||
	    (prev - r->r_items) / ggen_threshold <
	    (next - r->r_items) / ggen_threshold)
		rvp_increase_ggen();

	if ((nfull - nitems) / service_threshold < nfull / service_threshold)
		rvp_ring_request_service(r);
}

static inline void
rvp_ring_put_buf(rvp_ring_t *r, rvp_buf_t b)
{
	rvp_ring_put_multiple(r, &b.b_word[0], b.b_nwords);
}

void rvp_rings_init(void);
int rvp_ring_stdinit(rvp_ring_t *);
bool rvp_ring_get_iovs(rvp_ring_t *, int, int, struct iovec **,
    const struct iovec *, uint32_t *);
bool rvp_ring_flush_to_fd(rvp_ring_t *, int, rvp_lastctx_t *);
ssize_t rvp_ring_discard_by_bytes(rvp_ring_t *, const ssize_t, uint32_t *);
void rvp_ring_put_interruption(rvp_ring_t *, rvp_ring_t *, int, int);

#endif /* _RVP_RING_H_ */
