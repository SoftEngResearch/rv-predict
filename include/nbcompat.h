/* Copyright (c) 2017 Runtime Verification, Inc.  All rights reserved. */
/*
 * NetBSD compatibility macros
 *
 * NetBSD provides a lot of handy macros that Linux does not.  Many
 * of the macros even have documentation---see https://man-k.org/.
 */

#ifndef _RVP_NBCOMPAT_H_
#define _RVP_NBCOMPAT_H_

#include <sys/param.h>
#include <stddef.h>

#ifndef __NetBSD__

#define _C_LABEL(x)     x
#define _C_LABEL_STRING(x)      x

#define	__strong_alias(alias,sym)					\
    __asm(".global " _C_LABEL_STRING(#alias) "\n"			\
	    _C_LABEL_STRING(#alias) " = " _C_LABEL_STRING(#sym));

#define	__weak_alias(alias,sym)					\
    __asm(".weak " _C_LABEL_STRING(#alias) "\n"			\
	    _C_LABEL_STRING(#alias) " = " _C_LABEL_STRING(#sym));

/* Macros for counting and rounding from NetBSD.
 *
 * Documentation from the NetBSD manual page, roundup(9):
 *
 *   The roundup() and rounddown() macros return an integer from rounding x up
 *   and down, respectively, to the next size.  The howmany() macro in turn
 *   reveals how many times size fits into x, rounding the residual up.

 *   The roundup2() macro also rounds up, but with the assumption that size is
 *   a power of two.  If x is indeed a power of two, powerof2() return 1.
 */
#ifndef howmany
#define howmany(x, y)   (((x)+((y)-1))/(y))
#endif
#ifndef roundup
#define roundup(x, y)   ((((x)+((y)-1))/(y))*(y))
#endif
#define rounddown(x,y)  (((x)/(y))*(y))
#define roundup2(x, m)  (((x) + (m) - 1) & ~((m) - 1))
#ifndef powerof2
#define powerof2(x)     ((((x)-1)&(x))==0)
#endif

#ifndef __dead
#define	__dead	__attribute__((__noreturn__))
#endif

#ifndef offsetof
#define offsetof __builtin_offsetof
#endif

#ifndef __arraycount
#define __arraycount(__a)	(sizeof(__a) / sizeof((__a)[0]))
#endif /* __arraycount */

#ifndef __aligned
#define __aligned(x)	__attribute__((__aligned__(x)))
#endif /* __aligned */

#ifndef __section
#define __section(x)	__attribute__((__section__(x)))
#endif /* __section */

#ifndef __packed
#define	__packed	__attribute__((__packed__))
#endif /* __packed */

/* From <sys/cdefs.h> on NetBSD: */

#define __printflike(fmtarg, firstvararg)       \
            __attribute__((__format__ (__printf__, fmtarg, firstvararg)))
#define __scanflike(fmtarg, firstvararg)        \
            __attribute__((__format__ (__scanf__, fmtarg, firstvararg)))
#define __format_arg(fmtarg)    __attribute__((__format_arg__ (fmtarg)))

/* __BIT(n): nth bit, where __BIT(0) == 0x1. */
#define	__BIT(__n)	\
    (((uintmax_t)(__n) >= NBBY * sizeof(uintmax_t)) ? 0 : ((uintmax_t)1 << (uintmax_t)((__n) & (NBBY * sizeof(uintmax_t) - 1))))

/* __BITS(m, n): bits m through n, m < n. */
#define	__BITS(__m, __n)	\
        ((__BIT(MAX((__m), (__n)) + 1) - 1) ^ (__BIT(MIN((__m), (__n))) - 1))

/* find least significant bit that is set */
#define __LOWEST_SET_BIT(__mask) ((((__mask) - 1) & (__mask)) ^ (__mask))

#define	__PRIuBIT	PRIuMAX
#define	__PRIuBITS	__PRIuBIT

#define	__PRIxBIT	PRIxMAX
#define	__PRIxBITS	__PRIxBIT

#define	__SHIFTOUT(__x, __mask) (((__x) & (__mask)) / __LOWEST_SET_BIT(__mask))
#define	__SHIFTIN(__x, __mask) ((__x) * __LOWEST_SET_BIT(__mask))
#define	__SHIFTOUT_MASK(__mask) __SHIFTOUT((__mask), (__mask))

#define	__unused	__attribute__((__unused__))

#endif /* __NetBSD__ */

#endif /* _RVP_NBCOMPAT_H_ */
