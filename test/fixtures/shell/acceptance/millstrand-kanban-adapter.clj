(ns ct.adapters.kanban-acceptance
  "Select the core Kanban v25 declarations for adapter acceptance."
  (:require [millstrand.api.millstrand.alpha :as millstrand]
            [ct.spools.kanban :as kanban]))

(millstrand/use-op! kanban/kanban
                    kanban/kanban-export)
(millstrand/use-query! kanban/kanban-cards
                       kanban/kanban-pending
                       kanban/kanban-epic-pending)
(millstrand/use-pattern! kanban/kanban-batch)
(millstrand/use-bin! kanban/kanban-dash)
