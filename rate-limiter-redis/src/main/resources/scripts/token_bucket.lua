local key = KEYS[1]
local tokens_requested = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local refill_rate = tonumber(ARGV[3])
local now = tonumber(ARGV[4])
local ttl = math.ceil(capacity / refill_rate)

local state = redis.call('HMGET', key, 'tokens', 'last_refill')
local tokens = tonumber(state[1])
local last_refill = tonumber(state[2])

if tokens == nil then
    tokens = capacity
    last_refill = now
else
    local time_passed = math.max(0, now - last_refill)
    local refill = time_passed * refill_rate
    tokens = math.min(capacity, tokens + refill)
end

if tokens >= tokens_requested then
    tokens = tokens - tokens_requested
    redis.call('HMSET', key, 'tokens', tokens, 'last_refill', now)
    redis.call('EXPIRE', key, ttl)
    return tokens
else
    return -1
end
