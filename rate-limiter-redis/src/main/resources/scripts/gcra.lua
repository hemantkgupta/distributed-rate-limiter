local key = KEYS[1]
local cost = tonumber(ARGV[1])
local emission_interval = tonumber(ARGV[2]) -- Time to regenerate one token (ms or us)
local burst_tolerance = tonumber(ARGV[3]) -- tau
local now = tonumber(ARGV[4]) -- Current time from the client or Redis TIME
local ttl = math.ceil(burst_tolerance / 1000) * 2 -- Buffer for expiry in seconds

local tat = redis.call('GET', key)
if not tat then
    tat = now
else
    tat = tonumber(tat)
end

-- allow_at is the earliest time this request could arrive given the burst budget
local allow_at = tat - burst_tolerance

if now >= allow_at then
    -- It's allowed! Move the theoretical arrival time forward
    local new_tat = math.max(now, tat) + (cost * emission_interval)
    redis.call('SET', key, new_tat, 'EX', math.max(1, ttl))
    
    -- Request is allowed, we return 0 to indicate zero retry delay needed
    return 0
else
    -- Request denied, return the exact number of milliseconds until we can retry
    return (allow_at - now)
end
