/* -*- c-set-style: "K&R"; c-basic-offset: 8 -*-
 *
 * native_offload: intercept execve of registered handler names and
 * redirect them to a host-side server over an abstract unix socket.
 *
 * Skeleton stage: only log matches; redirection is implemented in a
 * follow-up commit.
 */

#ifndef NATIVE_OFFLOAD_H
#define NATIVE_OFFLOAD_H

#include "extension/extension.h"

/* Default abstract-socket name used when --native-offload is passed
 * without a value.  */
#define NATIVE_OFFLOAD_DEFAULT_SOCKET "native-offload"

/* Register a handler name.  Matched against argv[0] basename of
 * every execve.  Safe to call multiple times.  */
extern int native_offload_add_handler(const char *name);

/* Callback for the PRoot extension machinery.  */
extern int native_offload_callback(Extension *extension, ExtensionEvent event,
				   intptr_t data1, intptr_t data2);

#endif /* NATIVE_OFFLOAD_H */
