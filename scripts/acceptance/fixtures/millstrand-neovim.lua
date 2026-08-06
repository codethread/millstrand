local connected = false
local evaluated = false

vim.api.nvim_create_user_command("ConjureConnect", function()
  connected = true
end, { nargs = "*" })
vim.api.nvim_create_user_command("ConjureEval", function()
  evaluated = true
end, { nargs = "*" })

vim.system = function(_, _, callback)
  callback({
    code = 0,
    stdout = '[{"name":"smoke","state":"running","nrepl":{"host":"127.0.0.1","port":4321}}]',
    stderr = "",
  })
end
vim.ui.select = function(items, _, callback)
  callback(items[1])
end

vim.cmd("runtime plugin/millstrand.lua")
assert(vim.fn.exists(":MillstrandConnect") == 2, "MillstrandConnect command was not registered")
assert(type(require("millstrand").connect) == "function", "millstrand.connect is not callable")

vim.cmd("MillstrandConnect")
assert(vim.wait(1000, function()
  return connected and evaluated
end, 10), "MillstrandConnect did not complete the mocked Conjure flow")

local original_notify = vim.notify
local schema_error
vim.notify = function(message, ...)
  if tostring(message):find("unknown state", 1, true) then
    schema_error = message
  end
end
vim.system = function(_, _, callback)
  callback({
    code = 0,
    stdout = '[{"name":"smoke","state":"future-state"}]',
    stderr = "",
  })
end
vim.cmd("MillstrandConnect")
assert(vim.wait(1000, function()
  return schema_error ~= nil
end, 10), "MillstrandConnect did not reject an unknown weaver state")
vim.notify = original_notify

vim.cmd("qa!")
