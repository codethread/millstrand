local M = {}

local init_form = "(do (in-ns 'user) (require '[millstrand.repl :as repl]))"

local function notify_error(message)
  vim.notify(message, vim.log.levels.ERROR, { title = "millstrand.nvim" })
end

local function has_command(name)
  return vim.fn.exists(":" .. name) == 2
end

local function truncate_middle(value, max_len)
  value = tostring(value or "")
  if #value <= max_len then
    return value
  end

  local head_len = math.floor((max_len - 1) / 2)
  local tail_len = max_len - 1 - head_len
  return value:sub(1, head_len) .. "…" .. value:sub(#value - tail_len + 1)
end

local function short_path(value)
  if not value or value == "" then
    return nil
  end

  local path = tostring(value)
  local home = vim.loop.os_homedir()
  if home and path:sub(1, #home) == home then
    path = "~" .. path:sub(#home + 1)
  end
  return truncate_middle(path, 48)
end

local function item_label(row)
  local name = row.name or "unnamed"
  local path = short_path(row.config_dir or row.cwd)
  local state = row.state or "unknown"
  local nrepl = row.nrepl
  local endpoint = "no nREPL"

  if type(nrepl) == "table" and nrepl.host and nrepl.port then
    endpoint = tostring(nrepl.host) .. ":" .. tostring(nrepl.port)
  end

  if path then
    return string.format("%s  —  %s  [%s, %s]", name, path, state, endpoint)
  end
  return string.format("%s  [%s, %s]", name, state, endpoint)
end

local function decode_weavers(output)
  local ok, decoded = pcall(vim.fn.json_decode, output)
  if not ok or type(decoded) ~= "table" then
    error("mill weaver list returned malformed JSON")
  end
  return decoded
end

local function running_weavers(rows)
  local running = {}
  for _, row in ipairs(rows) do
    if type(row) == "table" and row.state == "running" then
      table.insert(running, row)
    end
  end
  return running
end

local function nrepl_endpoint(row)
  if type(row.nrepl) ~= "table" then
    return nil, nil, "chosen weaver lacks nREPL metadata"
  end

  local host = row.nrepl.host
  local port = tonumber(row.nrepl.port)
  if type(host) ~= "string" or host == "" then
    return nil, nil, "chosen weaver lacks nREPL host"
  end
  if not port or port ~= math.floor(port) then
    return nil, nil, "chosen weaver lacks nREPL port"
  end

  return host, port, nil
end

local function shell_error(result)
  local detail = result.stderr or result.stdout or ""
  if type(detail) == "table" then
    detail = table.concat(detail, "\n")
  end
  if detail == "" then
    detail = "exit code " .. tostring(result.code)
  end
  return detail
end

local function handle_weaver_list_result(result, on_success)
  if result.code ~= 0 then
    notify_error("mill weaver list failed: " .. shell_error(result))
    return
  end

  local ok, rows_or_err = pcall(decode_weavers, result.stdout or "")
  if not ok then
    notify_error(rows_or_err)
    return
  end

  local running = running_weavers(rows_or_err)
  if #running == 0 then
    notify_error("no running Millstrand weavers found; start one with :!mill weaver start")
    return
  end

  on_success(running)
end

local function list_weavers(on_success)
  if vim.fn.executable("mill") ~= 1 then
    notify_error("mill executable not found on PATH; install Millstrand and start mill first")
    return
  end

  if vim.system then
    vim.system({ "mill", "weaver", "list" }, { text = true }, function(result)
      vim.schedule(function()
        handle_weaver_list_result(result, on_success)
      end)
    end)
    return
  end

  local output = vim.fn.system({ "mill", "weaver", "list" })
  handle_weaver_list_result({
    code = vim.v.shell_error,
    stdout = output,
    stderr = "",
  }, on_success)
end

local function connect_row(row)
  if not has_command("ConjureConnect") then
    notify_error("Conjure command :ConjureConnect is unavailable; ensure Olical/conjure is loaded")
    return
  end
  if not has_command("ConjureEval") then
    notify_error("Conjure command :ConjureEval is unavailable; ensure Olical/conjure is loaded")
    return
  end

  local host, port, err = nrepl_endpoint(row)
  if err then
    notify_error(err)
    return
  end

  local endpoint = string.format("%s:%d", host, port)
  local connect_ok, connect_err = pcall(vim.cmd, string.format("ConjureConnect %s %d", vim.fn.fnameescape(host), port))
  if not connect_ok then
    notify_error("ConjureConnect failed for " .. endpoint .. ": " .. tostring(connect_err))
    return
  end

  local eval_ok, eval_err = pcall(vim.cmd, "ConjureEval " .. init_form)
  if not eval_ok then
    notify_error("ConjureEval failed while initializing millstrand.repl: " .. tostring(eval_err))
    return
  end

  vim.notify("Connected Conjure to Millstrand weaver " .. tostring(row.name or endpoint), vim.log.levels.INFO, { title = "millstrand.nvim" })
end

function M.connect()
  list_weavers(function(rows)
    vim.ui.select(rows, {
      prompt = "Millstrand weaver:",
      format_item = item_label,
    }, function(choice)
      if choice then
        connect_row(choice)
      end
    end)
  end)
end

return M
