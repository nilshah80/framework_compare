-- DELETE benchmark: each request targets a unique order ID.
-- Requires env DELETE_START_ID set to the first available order ID.
-- Base URL should be just host:port (e.g., http://localhost:8081)
-- Each wrk thread gets non-overlapping IDs via stride interleaving.

local counter
local stride = 4
local thread_count = 0

function setup(thread)
    thread:set("id", thread_count)
    thread_count = thread_count + 1
end

function init(args)
    local start = tonumber(os.getenv("DELETE_START_ID")) or 1
    local tid = wrk.thread:get("id")
    if thread_count > 0 then stride = thread_count end
    counter = start + tid
end

function request()
    local id = counter
    counter = counter + stride
    local user = os.getenv("DELETE_USER") or "user123"
    return wrk.format("DELETE", "/users/" .. user .. "/orders/" .. id, {["X-Api-Key"] = "bench-token"})
end
