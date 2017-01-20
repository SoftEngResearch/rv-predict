/* Copyright (c) 2017 Runtime Verification, Inc.  All rights reserved. */

#ifndef _RVP_THREAD_H_
#define _RVP_THREAD_H_

#include <err.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdlib.h>	/* for EXIT_FAILURE */

#include "ring.h"

typedef struct _rvp_thread rvp_thread_t;

struct _rvp_thread {
	pthread_t		t_pthread;
	uint32_t		t_id;
	rvp_thread_t * volatile	t_next;
	void			*t_arg;
	void			*(*t_routine)(void *);
	rvp_ring_t		t_ring;
};

int __rvpredict_pthread_create(pthread_t *, const pthread_attr_t *,
    void *(*)(void *), void *);
void __rvpredict_pthread_exit(void *);
int __rvpredict_pthread_join(pthread_t, void **);

bool rvp_thread_flush_to_fd(rvp_thread_t *, int, bool);

extern pthread_key_t rvp_thread_key;

static inline rvp_thread_t *
rvp_thread_for_curthr(void)
{
	rvp_thread_t *t;

	if ((t = pthread_getspecific(rvp_thread_key)) == NULL)
		errx(EXIT_FAILURE, "%s: pthread_getspecific -> NULL", __func__);

	return t;
}

static inline rvp_ring_t *
rvp_ring_for_curthr(void)
{
	rvp_thread_t *t = rvp_thread_for_curthr();

	return &t->t_ring;
}

#endif /* _RVP_THREAD_H_ */