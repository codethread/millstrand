(ns ct.adapters.kanban
  "Select the core Kanban declarations this workspace publishes.

  Kanban remains an external spool. Its namespace defines inert declaration
  Vars; this workspace-owned module selects only the board surface for its own
  owner partition."
  (:require [millstrand.api.millstrand.alpha :as millstrand]
            [ct.spools.kanban :as kanban]))

(millstrand/use-op! kanban/kanban
                    kanban/kanban-export)
(millstrand/use-query! kanban/kanban-cards
                       kanban/kanban-pending
                       kanban/kanban-epic-pending)
(millstrand/use-pattern! kanban/kanban-batch)
(millstrand/use-bin! kanban/kanban-dash)
