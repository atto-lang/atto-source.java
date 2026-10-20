#!/bin/sh
# Compatibility tombstone: checkout-local code is not a publication trust root.
# Real publication must use the independently reviewed external bootstrap,
# which authenticates and executes exact publisher bytes from protected source
# main. Never execute this checkout's publisher directly.
set -eu
PATH=/usr/bin:/bin:/usr/local/bin
export PATH
unset BASH_ENV ENV BASHOPTS SHELLOPTS CDPATH GLOBIGNORE PROMPT_COMMAND
unset PYTHONPATH PYTHONHOME PYTHONINSPECT
unset LD_PRELOAD LD_LIBRARY_PATH DYLD_INSERT_LIBRARIES DYLD_LIBRARY_PATH
printf '%s\n' \
  'PRIVATE_CI_DESTINATION_REJECTED: independent reviewed publisher bootstrap required; direct checkout-local publication is disabled' >&2
exit 1
