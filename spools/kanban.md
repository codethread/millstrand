# Skein Kanban Spool

`ct.spools.kanban` has moved to the external git-distributed spool repo:
[`codethread/kanban.spool`](https://github.com/codethread/kanban.spool).

The v22 contract doc and cookbook are snapshot links pinned to the release's peeled commit `d6b0cbe2b9650d261305f63334686a181f06de9e`: [`kanban.md`](https://github.com/codethread/kanban.spool/blob/d6b0cbe2b9650d261305f63334686a181f06de9e/kanban.md) and [`kanban.cookbook.md`](https://github.com/codethread/kanban.spool/blob/d6b0cbe2b9650d261305f63334686a181f06de9e/kanban.cookbook.md).

This checkout pins the spool in `.skein/spools.edn`. [External spool
consumption](./README.md#external-spool-consumption) in the spool index explains how this repo
consumes external spools, including the developer `spools.local.edn` override.

Kanban loads independently of devflow. This checkout activates the devflow-kanban adapter after both spools; v19 needs no consumer-owned tracker seed.
