# Skein Kanban Spool

`ct.spools.kanban` has moved to the external git-distributed spool repo:
[`codethread/kanban.spool`](https://github.com/codethread/kanban.spool).

The v16 contract doc and cookbook are snapshot links pinned to the release's peeled commit
`93fa591c1a64e30a79ebbc99d8b1456b88c4e85c`:
[`kanban.md`](https://github.com/codethread/kanban.spool/blob/93fa591c1a64e30a79ebbc99d8b1456b88c4e85c/kanban.md)
and [`kanban.cookbook.md`](https://github.com/codethread/kanban.spool/blob/93fa591c1a64e30a79ebbc99d8b1456b88c4e85c/kanban.cookbook.md).

This checkout pins the spool in `.skein/spools.edn`. [External spool
consumption](./README.md#external-spool-consumption) in the spool index explains how this repo
consumes external spools, including the developer `spools.local.edn` override.

Kanban loads independently of devflow. Only the tracker adapter in
`.skein/kanban_tracker.clj` depends on both spools: it binds devflow as kanban's tracker
once both are active.
