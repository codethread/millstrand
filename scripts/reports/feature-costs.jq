def malformed($strand; $attribute; $value):
  error("malformed agent-run attribute: strand=\($strand.id) attribute=\($attribute) value=\($value | tojson)");

def present($strand; $attribute):
  $strand.attributes | has($attribute);

def double($strand; $attribute):
  if present($strand; $attribute) | not then null
  else
    $strand.attributes[$attribute] as $value
    | try ($value | tonumber)
      catch malformed($strand; $attribute; $value)
  end;

def integer($strand; $attribute):
  if present($strand; $attribute) | not then null
  else
    $strand.attributes[$attribute] as $value
    | (try ($value | tonumber)
       catch malformed($strand; $attribute; $value)) as $number
    | if ($number | isfinite) and ($number | floor == $number)
      then $number
      else malformed($strand; $attribute; $value)
      end
  end;

def token_map($strand):
  "agent-run/tokens" as $attribute
  | if present($strand; $attribute) | not then null
    else
      $strand.attributes[$attribute] as $value
      | (try
           (if $value | type == "object" then $value
            elif $value | type == "string" then $value | fromjson
            else malformed($strand; $attribute; $value)
            end)
         catch malformed($strand; $attribute; $value)) as $tokens
      | if $tokens | type == "object"
        then $tokens
        else malformed($strand; $attribute; $value)
        end
    end;

def instant($strand; $attribute):
  if present($strand; $attribute) | not then null
  else
    $strand.attributes[$attribute] as $value
    | (try
         (($value | [capture(
           "^(?<base>[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(?<fraction>\\.[0-9]+)?(?<zone>Z|(?<sign>[+-])(?<offset-hour>[0-9]{2}):(?<offset-minute>[0-9]{2}))$"
         )]) as $matches
         | (if ($matches | length) == 1
            then $matches[0]
            else error("invalid instant")
            end) as $parts
          | (if $parts.zone == "Z"
             then 0
             elif (($parts["offset-hour"] | tonumber) <= 23
                   and ($parts["offset-minute"] | tonumber) <= 59)
             then ((($parts["offset-hour"] | tonumber) * 60
                    + ($parts["offset-minute"] | tonumber)) * 60
                   * (if $parts.sign == "+" then 1 else -1 end))
             else error("invalid offset")
             end) as $offset
          | (($parts.base + "Z" | fromdateiso8601)
             + (($parts.fraction // "0") | tonumber)
             - $offset) as $epoch
          | {text: $value, epoch: $epoch})
       catch malformed($strand; $attribute; $value))
  end;

def run_row($strand):
  instant($strand; "agent-run/started-at") as $started
  | instant($strand; "agent-run/finished-at") as $finished
  | {
      id: $strand.id,
      title: $strand.title,
      state: $strand.state,
      harness: $strand.attributes["agent-run/harness"],
      attempt: integer($strand; "agent-run/attempt"),
      "exit-code": integer($strand; "agent-run/exit-code"),
      "cost-usd": double($strand; "agent-run/cost-usd"),
      "tokens-total": integer($strand; "agent-run/tokens-total"),
      tokens: token_map($strand),
      "usage-source": $strand.attributes["agent-run/usage-source"],
      "session-id": $strand.attributes["agent-run/session-id"],
      "started-at": $started.text,
      "finished-at": $finished.text,
      "duration-secs": (
        if $started != null and $finished != null
        then $finished.epoch - $started.epoch
        else null
        end
      ),
      "_started-epoch": $started.epoch,
      "_finished-epoch": $finished.epoch
    };

def usage_rollup($rows):
  {
    runs: ($rows | length),
    "runs-with-usage": ([$rows[] | select(."cost-usd" != null)] | length),
    "cost-usd": ([$rows[]."cost-usd" | select(. != null)] | add // 0),
    "tokens-total": ([$rows[]."tokens-total" | select(. != null)] | add // 0)
  };

def wall_clock($rows):
  [$rows[] | select(."_started-epoch" != null)] as $starts
  | [$rows[] | select(."_finished-epoch" != null)] as $finishes
  | if ($starts | length) == 0 or ($finishes | length) == 0 then null
    else
      ($starts | min_by(."_started-epoch")) as $first
      | ($finishes | max_by(."_finished-epoch")) as $last
      | {
          "started-at": $first."started-at",
          "finished-at": $last."finished-at",
          "duration-secs": ($last."_finished-epoch" - $first."_started-epoch")
        }
    end;

def public_row:
  del(."_started-epoch", ."_finished-epoch");

def root_strand($subgraph):
  if ($subgraph.root_ids | length) != 1
  then error(
    "feature cost report requires exactly one root id: count=\($subgraph.root_ids | length) root_ids=\($subgraph.root_ids | tojson)"
  )
  else
    $subgraph.root_ids[0] as $root_id
    | [$subgraph.strands[] | select(.id == $root_id)] as $roots
    | if ($roots | length) == 1
      then $roots[0]
      else error("feature cost report root not found in subgraph: \($root_id)")
      end
  end;

. as $subgraph
| ($subgraph.strands
   | map(
       select(.attributes | has("agent-run/run"))
       | if .attributes["agent-run/run"] == true or .attributes["agent-run/run"] == "true"
         then run_row(.)
         else malformed(.; "agent-run/run"; .attributes["agent-run/run"])
         end
     )
   | sort_by(."started-at", .id)) as $rows
| root_strand($subgraph) as $root
| {
    root: {id: $root.id, title: $root.title, state: $root.state},
    runs: ($rows | map(public_row)),
    totals: (usage_rollup($rows) + {"wall-clock": wall_clock($rows)}),
    "by-harness": (
      $rows
      | sort_by(.harness)
      | group_by(.harness)
      | map(usage_rollup(.) + {harness: .[0].harness})
      | sort_by(-."cost-usd")
    ),
    "missing-usage": [$rows[] | select(."cost-usd" == null) | .id]
  }
