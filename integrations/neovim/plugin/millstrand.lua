if vim.g.loaded_millstrand_nvim then
  return
end
vim.g.loaded_millstrand_nvim = true

vim.api.nvim_create_user_command("MillstrandConnect", function(opts)
  require("millstrand").connect(opts)
end, {
  desc = "Select a running Millstrand weaver and connect Conjure to its nREPL",
})
