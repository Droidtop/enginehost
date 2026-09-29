-- Enginehost conformance test game for the love2d plugin.
--
-- MIT licence -- test-games/LICENSE.  LÖVE 11 API.  It exercises the three
-- things every conformance game must: a frame that changes on its own (the
-- moving square), an input response (any key, mouse button or touch flips
-- the phase), and a save (the phase is written to the identity save
-- directory and read back on the next launch, so the relaunch check
-- verifies the save survived).

local phase = 1
local note = "no save yet"
local time = 0.0

local function write_save()
    love.filesystem.write("conformance.txt", tostring(phase))
end

local function read_save()
    if love.filesystem.getInfo("conformance.txt") then
        return love.filesystem.read("conformance.txt")
    end
    return nil
end

local function flip()
    phase = (phase == 1) and 2 or 1
    write_save()
    note = "saved phase " .. phase
end

function love.load()
    local saved = read_save()
    if saved then
        phase = tonumber(saved) or 1
        note = "loaded save, phase " .. phase
    end
end

function love.update(dt)
    time = time + dt
end

function love.draw()
    if phase == 1 then
        love.graphics.setColor(0.22, 0.35, 0.6)
    else
        love.graphics.setColor(0.6, 0.22, 0.35)
    end
    love.graphics.rectangle("fill", 0, 0, 800, 480)

    -- The square moves every frame, so the screen is never static.
    love.graphics.setColor(0.9, 0.8, 0.3)
    local x = (time * 160) % 800
    love.graphics.rectangle("fill", x, 220, 40, 40)

    love.graphics.setColor(1, 1, 1)
    love.graphics.print("phase " .. phase, 16, 16)
    love.graphics.print(note, 16, 40)
    love.graphics.print("press any key or touch: flips the phase and saves", 16, 62)
end

function love.keypressed()
    flip()
end

function love.mousepressed()
    flip()
end

function love.touchpressed()
    flip()
end
