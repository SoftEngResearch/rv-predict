/* Copyright (c) 2017 Runtime Verification, Inc.  All rights reserved. */

#ifndef _RVP_INTR_H_
#define _RVP_INTR_H_

#include <stdint.h>

void __rvpredict_intr_register(void (*)(void), int32_t);
extern const char __data_registers_begin;
extern const char __data_registers_end;

#endif /* _RVP_INTR_H_ */

