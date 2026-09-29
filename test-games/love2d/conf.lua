-- Enginehost conformance test game for the love2d plugin.
--
-- MIT licence -- test-games/LICENSE.  LÖVE 11 API, matching the plugin's
-- 11 capability series.  See docs/conformance-test-games.md.
--
-- The identity below names the save directory LÖVE writes to under the
-- engine's save root, and conf.lua with function love.conf is one of the
-- two shapes Enginehost's detection accepts for an unpacked LÖVE game.

function love.conf(t)
    t.window.title = "Enginehost conformance test"
    t.identity = "enginehost-conformance-love2d"
end
