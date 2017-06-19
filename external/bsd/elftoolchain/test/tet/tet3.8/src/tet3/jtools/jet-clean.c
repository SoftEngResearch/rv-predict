/*
 *	SCCS: @(#)jet-clean.c	1.1 (99/09/02)
 *
 *	UniSoft Ltd., London, England
 *
 * Copyright (c) 1999 The Open Group
 * All rights reserved.
 *
 * No part of this source code may be reproduced, stored in a retrieval
 * system, or transmitted, in any form or by any means, electronic,
 * mechanical, photocopying, recording or otherwise, except as stated
 * in the end-user licence agreement, without the prior permission of
 * the copyright owners.
 * A copy of the end-user licence agreement is contained in the file
 * Licence which accompanies this distribution.
 * 
 * Motif, OSF/1, UNIX and the "X" device are registered trademarks and
 * IT DialTone and The Open Group are trademarks of The Open Group in
 * the US and other countries.
 *
 * X/Open is a trademark of X/Open Company Limited in the UK and other
 * countries.
 *
 */

#ifndef lint
static char sccsid[] = "@(#)jet-clean.c	1.1 (99/09/02) TETware release 3.8";
#endif

/************************************************************************

SCCS:		@(#)jet-clean.c	1.1 99/09/02 TETware release 3.8
NAME:		jet-clean.c
PRODUCT:	TETware
AUTHOR:		(From JETPack sources) Matthew Hails, UniSoft Ltd.
DATE CREATED:	9 July 1999

DESCRIPTION:
	Clean tool for TETware Java API. Removes class file generated by
	Java compiler.

************************************************************************/

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include "jtools.h"

int
main(int argc, char** argv)
{
	static char extn[] = ".class";
	char classfile[MAXPATH];

	/* Verify we have exactly one argument */
	if (argc != 2)
		jt_err(argv[0], "incorrect argument count");

	/* Construct name of .class file */
	sprintf(classfile, "%.*s%s", (int)(sizeof(classfile) - strlen(extn)),
		argv[1], extn);

	/* Remove .class file. It it not an error if it doesn't exist */
	errno = 0;
	if (remove(classfile) != 0 && errno != ENOENT)
		jt_err(argv[0], "error removing file \"%s\": %s", classfile,
			strerror(errno));

	return EXIT_SUCCESS;
}