(ns ct.adapters.kanban
  "Select the exact Kanban v24 declarations this workspace publishes.

  Kanban remains an unchanged external spool. Its namespace defines inert
  declaration Vars; this workspace-owned module selects the public board
  surface and lifecycle resource for its own owner partition."
  (:require [millstrand.api.lifecycle.alpha :as lifecycle]
            [millstrand.api.millstrand.alpha :as millstrand]
            [ct.spools.kanban :as kanban]))

(millstrand/use-op! kanban/kanban
                    kanban/kanban-export)
(millstrand/use-query! kanban/kanban-cards
                       kanban/kanban-pending
                       kanban/kanban-epic-pending)
(millstrand/use-pattern! kanban/kanban-batch)
(millstrand/use-bin! kanban/kanban-dash)
(lifecycle/use-resource! kanban/kanban-runtime)
