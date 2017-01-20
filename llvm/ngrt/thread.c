/* Copyright (c) 2017 Runtime Verification, Inc.  All rights reserved. */

#include <assert.h>
#include <err.h> /* for err(3) */
#include <errno.h> /* for ESRCH */
#include <stdint.h> /* for uint32_t */
#include <stdlib.h> /* for EXIT_FAILURE */
#include <stdio.h> /* for fprintf(3) */
#include <string.h> /* for strerror(3) */
#include <unistd.h> /* for sysconf */

#include "init.h"
#include "thread.h"
#include "trace.h"

static rvp_thread_t *rvp_thread_create(void *(*)(void *), void *);

static long pgsz = 0;

/* thread_mutex protects thread_head, next_id */
static pthread_mutex_t thread_mutex;
static pthread_cond_t wakecond;
static int nwake = 0;
static rvp_thread_t * volatile thread_head = NULL;
static uint32_t next_id = 0;
static pthread_t serializer;
static int serializer_fd;

pthread_key_t rvp_thread_key;
static pthread_once_t rvp_init_once = PTHREAD_ONCE_INIT;

static void rvp_thread0_create(void);

static inline void
rvp_trace_fork(uint32_t id)
{
	rvp_thread_t *t = rvp_thread_for_curthr();
	rvp_ring_t *r = &t->t_ring;
	rvp_ring_put_pc_and_op(r, __builtin_return_address(1), RVP_OP_FORK);
	rvp_ring_put(r, id);
}

static inline void
rvp_trace_join(uint32_t id)
{
	rvp_thread_t *t = rvp_thread_for_curthr();
	rvp_ring_t *r = &t->t_ring;
	rvp_ring_put_pc_and_op(r, __builtin_return_address(1), RVP_OP_JOIN);
	rvp_ring_put(r, id);
}

static inline void
rvp_trace_end(void)
{
	rvp_ring_t *r = rvp_ring_for_curthr();
	rvp_ring_put_pc_and_op(r, __builtin_return_address(1), RVP_OP_END);
}

static void
rvp_thread0_create(void)
{
	rvp_thread_t *t;

	if ((t = rvp_thread_create(NULL, NULL)) == NULL)
		err(EXIT_FAILURE, "%s: rvp_thread_create", __func__);

	if (pthread_setspecific(rvp_thread_key, t) != 0)
		err(EXIT_FAILURE, "%s: pthread_setspecific", __func__);

	t->t_pthread = pthread_self();

	assert(t->t_id == 1);

	rvp_ring_put_begin(&t->t_ring, t->t_id);
}

static void
thread_lock(void)
{
	if (pthread_mutex_lock(&thread_mutex) != 0)
		err(EXIT_FAILURE, "%s: pthread_mutex_lock", __func__);
}

static void
thread_unlock(void)
{
	if (pthread_mutex_unlock(&thread_mutex) != 0)
		err(EXIT_FAILURE, "%s: pthread_mutex_unlock", __func__);
}

static void *
serialize(void *arg)
{
	int fd = serializer_fd;

	thread_lock();
	for (;;) {
		bool any_emptied;

		while (nwake == 0) {
			int rc = pthread_cond_wait(&wakecond, &thread_mutex);
			if (rc != 0) {
				errx(EXIT_FAILURE, "%s: pthread_cond_wait: %s",
				    __func__, strerror(rc));
			}
		}
		nwake--;
//		fprintf(stderr, "%s: woke up\n", __func__);
		do {
			rvp_thread_t *t, *last_t = NULL;
			any_emptied = false;
			for (t = thread_head; t != NULL; t = t->t_next) {
				if (rvp_thread_flush_to_fd(t, fd, t != last_t)){
					last_t = t;
					any_emptied = true; 
				}
			}
		} while (any_emptied);
	}
	thread_unlock();
	return NULL;
}

static void
rvp_serializer_create(void)
{
	int rc;

	if ((serializer_fd = rvp_trace_open()) == -1)
		err(EXIT_FAILURE, "%s: rvp_trace_open", __func__);

	thread_lock();
	assert(thread_head->t_next == NULL);
	rvp_thread_flush_to_fd(thread_head, serializer_fd, false);
	thread_unlock();

	if ((rc = pthread_create(&serializer, NULL, serialize, NULL)) != 0) {
		errx(EXIT_FAILURE, "%s: pthread_create: %s", __func__,
		    strerror(rc));
	}
}

static void
rvp_init(void)
{
	if (pgsz == 0 && (pgsz = sysconf(_SC_PAGE_SIZE)) == -1)
		err(EXIT_FAILURE, "%s: sysconf", __func__);
	if (pthread_key_create(&rvp_thread_key, NULL) != 0) 
		err(EXIT_FAILURE, "%s: pthread_key_create", __func__);
	if (pthread_mutex_init(&thread_mutex, NULL) != 0)
		err(EXIT_FAILURE, "%s: pthread_mutex_init", __func__);
	if (pthread_cond_init(&wakecond, NULL) != 0)
		err(EXIT_FAILURE, "%s: pthread_mutex_init", __func__);
	/* The 'begin' op for the first thread (tid 1) has to be
	 * written directly after the header, and it is by virtue of
	 * rvp_serializer_create() being called directly after
	 * rvp_thread0_create(), before any other thread has an opportunity
	 * to start.
	 */
	rvp_thread0_create();
	rvp_serializer_create();
}

void
__rvpredict_init(void)
{
	(void)pthread_once(&rvp_init_once, rvp_init);
}

static void *
__rvpredict_thread_wrapper(void *arg)
{
	void *retval;
	rvp_thread_t *t = arg;

	assert(pthread_getspecific(rvp_thread_key) == NULL);

	if (pthread_setspecific(rvp_thread_key, t) != 0)
		err(EXIT_FAILURE, "%s: pthread_setspecific", __func__);

	rvp_ring_put_begin(&t->t_ring, t->t_id);

	retval = (*t->t_routine)(t->t_arg);
	__rvpredict_pthread_exit(retval);
	/* probably never reached */
	return retval;
}

static int
rvp_thread_attach(rvp_thread_t *t)
{
	thread_lock();

	if ((t->t_id = ++next_id) == 0) {
		thread_unlock();
		errx(EXIT_FAILURE, "%s: out of thread IDs", __func__);
	}

	t->t_next = thread_head;
	thread_head = t;

	thread_unlock();

	return 0;
}

static int
rvp_thread_detach(rvp_thread_t *tgt)
{
	rvp_thread_t * volatile *tp;
	int rc;

	thread_lock();

	for (tp = &thread_head; *tp != NULL && *tp != tgt; tp = &(*tp)->t_next)
		;

	if (*tp != NULL) {
		*tp = (*tp)->t_next;
		rc = 0;
	} else
		rc = ENOENT;

	thread_unlock();

	return rc;
}

static void
rvp_thread_destroy(rvp_thread_t *t)
{
	if (rvp_thread_detach(t) != 0)
		err(EXIT_FAILURE, "%s: rvp_thread_detach", __func__);

	free(t->t_ring.r_items);
	free(t);
}

static rvp_thread_t *
rvp_thread_create(void *(*routine)(void *), void *arg)
{
	rvp_thread_t *t;
	const size_t items_per_pg = pgsz / sizeof(*t->t_ring.r_items);
	uint32_t *items;

	items = calloc(items_per_pg, sizeof(*t->t_ring.r_items));
	if (items == NULL) {
		errno = ENOMEM;
		return NULL;
	}

	if ((t = calloc(1, sizeof(*t))) == NULL) {
		free(items);
		errno = ENOMEM;
		return NULL;
	}

	t->t_routine = routine;
	t->t_arg = arg;

	rvp_ring_init(&t->t_ring, items, items_per_pg);

	rvp_thread_attach(t);

	return t;
}

int
__rvpredict_pthread_create(pthread_t *thread,
    const pthread_attr_t *attr, void *(*start_routine) (void *), void *arg)
{
	int rc;
	rvp_thread_t *t;

	assert(pgsz != 0);

	if ((t = rvp_thread_create(start_routine, arg)) == NULL)
		return errno;

	rc = pthread_create(&t->t_pthread, attr, __rvpredict_thread_wrapper, t);

	if (rc == 0) {
		*thread = t->t_pthread;
		rvp_trace_fork(t->t_id);
		return 0;
	}

	rvp_thread_destroy(t);

	return rc;
}

static rvp_thread_t *
rvp_pthread_to_thread(pthread_t pthread)
{
	rvp_thread_t *t;

	thread_lock();

	for (t = thread_head; t != NULL; t = t->t_next) {
		if (t->t_pthread == pthread)
			break;
	}

	thread_unlock();

	if (t == NULL)
		errno = ESRCH;

	return t;
}

void
__rvpredict_pthread_exit(void *retval)
{
	pthread_exit(retval);
	rvp_trace_end();
	/* TBD flag change of status so that we can flush the trace
	 * and reclaim resources---e.g., munmap/free the ring
	 * once it's empty.  Careful: need to hang around for _join().
	 */
}

int
__rvpredict_pthread_join(pthread_t pthread, void **retval)
{
	int rc;
	rvp_thread_t *t;

	if ((rc = pthread_join(pthread, retval)) != 0)
		return rc;

	if ((t = rvp_pthread_to_thread(pthread)) == NULL)
		err(EXIT_FAILURE, "%s: rvp_pthread_to_thread", __func__);

	rvp_trace_join(t->t_id);

	/* TBD don't destroy, mark as garbage! */
	rvp_thread_destroy(t);

	return 0;
}

void
rvp_wake_transmitter(void)
{
	int rc;
	thread_lock();
	nwake++;
	if ((rc = pthread_cond_signal(&wakecond)) != 0)
		errx(EXIT_FAILURE, "%s: %s", __func__, strerror(rc));
	thread_unlock();
}
