/********************************************************************\
 Copyright (c) 2017 by Aleksey Cheusov

 See LICENSE file in the distribution.
\********************************************************************/

#ifndef _MKC_FPARSELN_H_
#define _MKC_FPARSELN_H_

#include <stdio.h>

#ifndef HAVE_FUNC5_FPARSELN_STDIO_H

#define FPARSELN_UNESCESC       0x01
#define FPARSELN_UNESCCONT      0x02
#define FPARSELN_UNESCCOMM      0x04
#define FPARSELN_UNESCREST      0x08
#define FPARSELN_UNESCALL       0x0f

char *fparseln(FILE *stream, size_t *len, size_t *lineno,
			   const char delim[3], int flags);
#endif

#endif // _MKC_FPARSELN_H_
