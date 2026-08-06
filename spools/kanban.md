# Millstrand Kanban Spool

`ct.spools.kanban` has moved to the external git-distributed spool repo:
[`codethread/kanban.spool`](https://github.com/codethread/kanban.spool).

The v23 contract doc and cookbook are snapshot links pinned to the release's peeled commit `2947590e7965feb95a239189af3bd55f008d1209`: [`kanban.md`](https://github.com/codethread/kanban.spool/blob/2947590e7965feb95a239189af3bd55f008d1209/kanban.md) and [`kanban.cookbook.md`](https://github.com/codethread/kanban.spool/blob/2947590e7965feb95a239189af3bd55f008d1209/kanban.cookbook.md).

This checkout pins the spool in `.skein/spools.edn`. [External spool
consumption](./README.md#external-spool-consumption) in the spool index explains how this repo
consumes external spools, including the developer `spools.local.edn` override.

Kanban loads independently of devflow. This checkout activates the devflow-kanban adapter after both spools; v20 needs no consumer-owned tracker seed.
